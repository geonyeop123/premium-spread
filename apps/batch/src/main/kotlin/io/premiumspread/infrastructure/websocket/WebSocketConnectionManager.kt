package io.premiumspread.infrastructure.websocket

import io.premiumspread.monitoring.AlertService
import org.slf4j.LoggerFactory
import org.springframework.web.reactive.socket.WebSocketSession
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient
import org.springframework.web.reactive.socket.client.WebSocketClient
import reactor.core.Disposable
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.SignalType
import reactor.core.scheduler.Schedulers
import java.net.URI
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class WebSocketConnectionManager(
    private val config: WebSocketConnectionConfig,
    private val metrics: WebSocketMetrics,
    private val client: WebSocketClient = ReactorNettyWebSocketClient(),
    private val alertService: AlertService,
    private val initialBackoff: Duration = Duration.ofSeconds(1),
    private val maxBackoff: Duration = Duration.ofSeconds(30),
    private val watchdogCheckInterval: Duration = Duration.ofSeconds(5),
) {
    private val log = LoggerFactory.getLogger("${javaClass.simpleName}[${config.exchange}]")
    private val subscription = AtomicReference<Disposable?>()
    private val pendingReconnect = AtomicReference<Disposable?>()
    private val pingDisposable = AtomicReference<Disposable?>()
    private val watchdogDisposable = AtomicReference<Disposable?>()
    private val running = AtomicBoolean(false)
    private val currentBackoff = AtomicReference(initialBackoff)
    private val lastMessageAt = AtomicLong(0L)
    // 연결 세대 카운터 — 매 connectWithRetry() 호출마다 증가.
    // watchdog/firstMessageTimeout 콜백이 자신이 속한 세대를 기억하고,
    // 현재 세대와 다르면 stale 콜백으로 간주해 force-reconnect를 발동하지 않는다 (ISSUE-1 대응).
    private val connectionGeneration = AtomicLong(0L)
    // watchdog 또는 firstMessageTimeout이 강제 재연결을 트리거했음을 표시.
    // doFinally의 CANCEL 가드가 stop()에 의한 cancel과 force-reconnect cancel을 구분하는 데 사용.
    private val forceReconnectArmed = AtomicBoolean(false)

    fun start() {
        if (!running.compareAndSet(false, true)) return
        connectWithRetry()
    }

    fun stop() {
        running.set(false)
        subscription.getAndSet(null)?.dispose()
        pendingReconnect.getAndSet(null)?.dispose()
        pingDisposable.getAndSet(null)?.dispose()
        watchdogDisposable.getAndSet(null)?.dispose()
        metrics.setConnectionState(config.exchange, connected = false)
    }

    private fun connectWithRetry() {
        if (!running.get()) return

        // 이 연결 세대 ID를 캡처. watchdog/firstMessageTimeout 콜백이 이 값과 현재 세대를 비교한다.
        val myGeneration = connectionGeneration.incrementAndGet()
        val firstReceived = AtomicBoolean(false)
        val timeoutDisposable = AtomicReference<Disposable?>()

        val mono = client.execute(URI.create(config.url)) { session: WebSocketSession ->
            metrics.setConnectionState(config.exchange, connected = true)
            // 핸드셰이크 성공 시점에 lastMessageAt 초기화 + watchdog 시작.
            // handshake-success/zero-message 케이스(연결만 되고 프레임 0건)에서도 idle 추적이 가능하도록
            // 첫 메시지를 기다리지 않고 즉시 시작한다 (Codex spec review ISSUE-2).
            val handshakeAt = metrics.currentEpochMilli()
            lastMessageAt.set(handshakeAt)
            startWatchdog(myGeneration)

            val sendInit: Mono<Void> = config.subscribeMessage
                ?.let { msg -> session.send(Mono.just(session.textMessage(msg))) }
                ?: Mono.empty()

            // Start first-message timeout as a separate cancellable side task.
            // 발동 시 force-reconnect를 먼저 트리거하고 alert는 boundedElastic로 분리 dispatch — alert delivery
            // (SlackAlertService RestTemplate)가 hang해도 parallel scheduler 워커를 점유하지 않고 재연결이 막히지 않도록.
            val timer = Mono.delay(config.firstMessageTimeout, Schedulers.parallel())
                .doOnNext {
                    if (firstReceived.compareAndSet(false, true).not()) return@doOnNext
                    if (myGeneration != connectionGeneration.get()) return@doOnNext  // stale callback (ISSUE-1)
                    metrics.recordFirstMessageTimeout(config.exchange)
                    triggerForceReconnect(myGeneration)
                    dispatchAlert(
                        "[${config.exchange}] WebSocket 연결 후 ${config.firstMessageTimeout.toSeconds()}초 내 메시지 미수신 — 강제 재연결"
                    )
                }
                .subscribe()
            timeoutDisposable.set(timer)

            // Start client-side ping if configured — runs as a separate cancellable stream
            when (val hb = config.heartbeat) {
                is HeartbeatPolicy.ClientPing -> {
                    val ping = Flux.interval(hb.interval, Schedulers.parallel())
                        .flatMap { session.send(Mono.just(session.textMessage(hb.pingMessage))) }
                        .subscribe()
                    pingDisposable.set(ping)
                }
                HeartbeatPolicy.None, HeartbeatPolicy.ServerPingResponse -> { /* no-op */ }
            }

            val receive: Mono<Void> = session.receive()
                .doOnNext { frame ->
                    val nowMs = metrics.currentEpochMilli()
                    // watchdog는 WebSocket 프레임 단위 idle 감지를 담당한다 (TCP-level zombie 회복).
                    // 거래소별 ticker 데이터 stream의 staleness는 *FlushJob의 STALE_THRESHOLD(10s)가 별도로 감지.
                    // 두 레이어가 직교하여 다른 종류의 outage(완전 침묵 vs ticker-only 침묵)를 각각 커버한다.
                    lastMessageAt.set(nowMs)
                    // Cancel timeout on first message arrival and reset backoff
                    if (firstReceived.compareAndSet(false, true)) {
                        timeoutDisposable.getAndSet(null)?.dispose()
                        currentBackoff.set(initialBackoff)  // 첫 메시지 수신 시 reset (Codex review P2)
                    }
                    val payload = frame.payloadAsText
                    metrics.recordMessage(config.exchange)
                    metrics.onMessageReceivedAt(config.exchange, nowMs)
                    try {
                        config.onMessage(payload)
                    } catch (e: Exception) {
                        log.warn("onMessage handler failed", e)
                    }
                }
                .then()

            sendInit.then(receive)
        }

        val disposable = mono
            .doOnError { e -> log.error("WebSocket connection error: {}", e.message) }
            .doFinally { signalType ->
                timeoutDisposable.getAndSet(null)?.dispose()  // resource cleanup, CANCEL 신호에서도 실행
                pingDisposable.getAndSet(null)?.dispose()      // ping stream cleanup, CANCEL 신호에서도 실행
                watchdogDisposable.getAndSet(null)?.dispose()  // watchdog cleanup, CANCEL 신호에서도 실행
                metrics.setConnectionState(config.exchange, connected = false)
                // CANCEL 신호 처리: force-reconnect armed인 경우만 reconnect 경로 진입, 아니면 stop()에 의한 cancel로 간주.
                if (signalType == SignalType.CANCEL && !forceReconnectArmed.compareAndSet(true, false)) {
                    return@doFinally
                }
                if (running.get()) {
                    val backoff = currentBackoff.get()
                    val next = (backoff.toMillis() * 2).coerceAtMost(maxBackoff.toMillis())
                    currentBackoff.set(Duration.ofMillis(next))
                    metrics.recordReconnect(config.exchange)
                    log.info("Reconnecting in {} ms", backoff.toMillis())
                    val timer = Mono.delay(backoff, Schedulers.parallel())
                        .doOnNext {
                            if (running.get()) connectWithRetry()
                        }
                        .subscribe()
                    pendingReconnect.getAndSet(timer)?.dispose()
                }
            }
            .subscribe()

        subscription.set(disposable)

        // Double-check: if stop() was called while we were subscribing, dispose the stale connection immediately
        if (!running.get()) {
            subscription.getAndSet(null)?.dispose()
        }
    }

    /**
     * watchdog를 시작한다. 매 [watchdogCheckInterval]마다 inbound 메시지 idle 시간을 검사해
     * `config.idleTimeout` 초과 시 강제 재연결을 트리거한다.
     *
     * 핸드셰이크 직후 호출되며, 기존 watchdog disposable이 있으면 dispose 후 새로 시작한다.
     *
     * @param ownerGeneration 이 watchdog가 속한 연결 세대. 콜백 발동 시 현재 세대와 다르면 stale로 간주한다.
     */
    private fun startWatchdog(ownerGeneration: Long) {
        val idleMs = config.idleTimeout.toMillis()
        val watchdog = Flux.interval(watchdogCheckInterval, watchdogCheckInterval, Schedulers.parallel())
            .doOnNext {
                if (ownerGeneration != connectionGeneration.get()) return@doOnNext  // stale callback (ISSUE-1)
                val last = lastMessageAt.get()
                if (last <= 0L) return@doOnNext // not yet primed (shouldn't happen since handshake sets it)
                val idle = metrics.currentEpochMilli() - last
                if (idle > idleMs) {
                    log.warn(
                        "WebSocket idle {}ms exceeds threshold {}ms — forcing reconnect",
                        idle,
                        idleMs,
                    )
                    // 재연결을 먼저 트리거하고 alert는 boundedElastic에서 분리 dispatch — alert delivery hang에도
                    // parallel scheduler 워커를 점유하지 않고 재연결 timer가 정상 동작하도록.
                    triggerForceReconnect(ownerGeneration)
                    dispatchAlert(
                        "[${config.exchange}] WebSocket idle ${idle / 1000}s (threshold ${idleMs / 1000}s) — 강제 재연결"
                    )
                }
            }
            .subscribe()
        watchdogDisposable.getAndSet(watchdog)?.dispose()
    }

    /**
     * 현재 연결을 강제로 종료하고 재연결 경로로 진입시킨다.
     *
     * 호출 세대가 현재 세대와 다르면 stale 콜백이므로 무시한다 (ISSUE-1 대응).
     *
     * [forceReconnectArmed]를 set한 후 subscription을 dispose하면 doFinally가 CANCEL을 받지만
     * armed 플래그를 CAS로 확인해 stop()에 의한 cancel과 구분한다.
     *
     * watchdog 또는 firstMessageTimeout 콜백에서 호출된다.
     */
    private fun triggerForceReconnect(callerGeneration: Long) {
        if (!running.get()) return
        if (callerGeneration != connectionGeneration.get()) return  // stale caller
        forceReconnectArmed.set(true)
        subscription.getAndSet(null)?.dispose()
    }

    /**
     * Alert delivery를 boundedElastic 스케줄러에서 fire-and-forget으로 dispatch한다.
     *
     * 운영의 SlackAlertService는 blocking RestTemplate 기반이고 timeout 미설정이라 hang 가능.
     * boundedElastic(blocking I/O 전용 스케줄러)에서 실행해 parallel 스케줄러를 점유하지 않도록 한다 —
     * 재연결 timer는 parallel 스케줄러에서 동작하므로 alert hang이 재연결을 막지 않는다 (ISSUE-2 대응).
     */
    private fun dispatchAlert(message: String) {
        Mono.fromRunnable<Unit> { alertService.sendCriticalAlert(message) }
            .subscribeOn(Schedulers.boundedElastic())
            .onErrorResume { t ->
                log.warn("Alert dispatch failed (suppressed): {}", t.message)
                Mono.empty()
            }
            .subscribe()
    }
}
