package io.premiumspread.infrastructure.websocket

import org.slf4j.LoggerFactory
import org.springframework.web.reactive.socket.WebSocketSession
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient
import org.springframework.web.reactive.socket.client.WebSocketClient
import reactor.core.Disposable
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import java.net.URI
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class WebSocketConnectionManager(
    private val config: WebSocketConnectionConfig,
    private val metrics: WebSocketMetrics,
    private val client: WebSocketClient = ReactorNettyWebSocketClient(),
    private val initialBackoff: Duration = Duration.ofSeconds(1),
    private val maxBackoff: Duration = Duration.ofSeconds(30),
) {
    private val log = LoggerFactory.getLogger("${javaClass.simpleName}[${config.exchange}]")
    private val subscription = AtomicReference<Disposable?>()
    private val pendingReconnect = AtomicReference<Disposable?>()
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
        metrics.setConnectionState(config.exchange, connected = false)
    }

    private fun connectWithRetry() {
        if (!running.get()) return

        val mono = client.execute(URI.create(config.url)) { session: WebSocketSession ->
            metrics.setConnectionState(config.exchange, connected = true)
            currentBackoff.set(initialBackoff)

            val sendInit: Mono<Void> = config.subscribeMessage
                ?.let { msg -> session.send(Mono.just(session.textMessage(msg))) }
                ?: Mono.empty()

            val receive: Mono<Void> = session.receive()
                .doOnNext { frame ->
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
            .doFinally {
                metrics.setConnectionState(config.exchange, connected = false)
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
