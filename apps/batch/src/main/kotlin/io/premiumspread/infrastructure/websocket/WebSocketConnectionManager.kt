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
import java.util.concurrent.atomic.AtomicReference

class WebSocketConnectionManager(
    private val config: WebSocketConnectionConfig,
    private val metrics: WebSocketMetrics,
    private val client: WebSocketClient = ReactorNettyWebSocketClient(),
    private val alertService: AlertService,
    private val initialBackoff: Duration = Duration.ofSeconds(1),
    private val maxBackoff: Duration = Duration.ofSeconds(30),
) {
    private val log = LoggerFactory.getLogger("${javaClass.simpleName}[${config.exchange}]")
    private val subscription = AtomicReference<Disposable?>()
    private val pendingReconnect = AtomicReference<Disposable?>()
    private val pingDisposable = AtomicReference<Disposable?>()
    private val running = AtomicBoolean(false)
    private val currentBackoff = AtomicReference(initialBackoff)

    fun start() {
        if (!running.compareAndSet(false, true)) return
        connectWithRetry()
    }

    fun stop() {
        running.set(false)
        subscription.getAndSet(null)?.dispose()
        pendingReconnect.getAndSet(null)?.dispose()
        pingDisposable.getAndSet(null)?.dispose()
        metrics.setConnectionState(config.exchange, connected = false)
    }

    private fun connectWithRetry() {
        if (!running.get()) return

        val firstReceived = AtomicBoolean(false)
        val timeoutDisposable = AtomicReference<Disposable?>()

        val mono = client.execute(URI.create(config.url)) { session: WebSocketSession ->
            metrics.setConnectionState(config.exchange, connected = true)
            // 첫 메시지 수신 후 backoff 리셋 (Codex review P2 대응)

            val sendInit: Mono<Void> = config.subscribeMessage
                ?.let { msg -> session.send(Mono.just(session.textMessage(msg))) }
                ?: Mono.empty()

            // Start first-message timeout as a separate cancellable side task
            val timer = Mono.delay(config.firstMessageTimeout, Schedulers.parallel())
                .doOnNext {
                    if (firstReceived.compareAndSet(false, true).not()) return@doOnNext
                    metrics.recordFirstMessageTimeout(config.exchange)
                    alertService.sendCriticalAlert(
                        "[${config.exchange}] WebSocket 연결 후 ${config.firstMessageTimeout.toSeconds()}초 내 메시지 미수신"
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
                    // Cancel timeout on first message arrival and reset backoff
                    if (firstReceived.compareAndSet(false, true)) {
                        timeoutDisposable.getAndSet(null)?.dispose()
                        currentBackoff.set(initialBackoff)  // 첫 메시지 수신 시 reset (Codex review P2)
                    }
                    val payload = frame.payloadAsText
                    metrics.recordMessage(config.exchange)
                    metrics.onMessageReceivedAt(config.exchange, System.currentTimeMillis())
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
                metrics.setConnectionState(config.exchange, connected = false)
                if (signalType == SignalType.CANCEL) return@doFinally  // stop()에 의한 취소 — backoff/메트릭 오염 방지
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
}
