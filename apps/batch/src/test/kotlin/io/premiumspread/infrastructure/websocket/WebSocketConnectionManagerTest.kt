package io.premiumspread.infrastructure.websocket

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

class WebSocketConnectionManagerTest {

    private lateinit var server: MockWebServer
    private val registry = SimpleMeterRegistry()
    private val metrics = WebSocketMetrics(registry)
    private val client = ReactorNettyWebSocketClient()
    private var manager: WebSocketConnectionManager? = null

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun tearDown() {
        manager?.stop()
        server.shutdown()
    }

    @Test
    fun `연결 성공 시 메시지 수신 콜백이 호출되고 메트릭이 기록된다`() {
        val received = ConcurrentLinkedQueue<String>()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                webSocket.send("""{"price":"100"}""")
            }
        }))

        val config = WebSocketConnectionConfig(
            exchange = "test",
            url = wsUrl(),
            onMessage = { received.add(it) },
            firstMessageTimeout = Duration.ofSeconds(2),
        )
        manager = WebSocketConnectionManager(config, metrics, client).also { it.start() }

        await().atMost(3, TimeUnit.SECONDS).untilAsserted {
            assertThat(received).contains("""{"price":"100"}""")
        }
        assertThat(registry.find("ws.connection.state").tag("exchange", "test").gauge()?.value()).isEqualTo(1.0)
        assertThat(registry.find("ws.message.received").tag("exchange", "test").counter()?.count()).isGreaterThanOrEqualTo(1.0)
    }

    private fun wsUrl(): String {
        val httpUrl = server.url("/ws")
        return "ws://${httpUrl.host}:${httpUrl.port}/ws"
    }
}
