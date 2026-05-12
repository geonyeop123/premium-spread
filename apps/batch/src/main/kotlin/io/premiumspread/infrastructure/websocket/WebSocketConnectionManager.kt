package io.premiumspread.infrastructure.websocket

import org.slf4j.LoggerFactory
import org.springframework.web.reactive.socket.WebSocketSession
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient
import org.springframework.web.reactive.socket.client.WebSocketClient
import reactor.core.Disposable
import reactor.core.publisher.Mono
import java.net.URI
import java.util.concurrent.atomic.AtomicReference

/**
 * 단일 WebSocket 연결의 lifecycle을 관리한다.
 *
 * 책임:
 * - 시작/종료 lifecycle
 * - 메시지 수신 → onMessage 콜백 + 메트릭
 *
 * 미구현 (Task 4/5/6에서 추가):
 * - 자동 재연결 + exponential backoff
 * - 하트비트 정책 적용
 * - 첫 메시지 타임아웃
 */
class WebSocketConnectionManager(
    private val config: WebSocketConnectionConfig,
    private val metrics: WebSocketMetrics,
    private val client: WebSocketClient = ReactorNettyWebSocketClient(),
) {
    private val log = LoggerFactory.getLogger("${javaClass.simpleName}[${config.exchange}]")
    private val subscription = AtomicReference<Disposable?>()

    fun start() {
        connect()
    }

    fun stop() {
        subscription.getAndSet(null)?.dispose()
        metrics.setConnectionState(config.exchange, connected = false)
    }

    private fun connect() {
        val mono = client.execute(URI.create(config.url)) { session: WebSocketSession ->
            metrics.setConnectionState(config.exchange, connected = true)

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
            .doOnError { e -> log.error("WebSocket connection error", e) }
            .doFinally { metrics.setConnectionState(config.exchange, connected = false) }
            .subscribe()

        subscription.set(disposable)
    }
}
