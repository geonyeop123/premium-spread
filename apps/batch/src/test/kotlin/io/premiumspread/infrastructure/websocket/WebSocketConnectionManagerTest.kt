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
import java.util.concurrent.atomic.AtomicInteger

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
        Thread.sleep(300) // allow reactor threads to drain before server shutdown
        try {
            server.shutdown()
        } catch (_: Exception) {
            // ignore shutdown timeout — connections will be GC'd after test
        }
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

    @Test
    fun `서버가 연결을 끊으면 재연결을 시도하고 ws_reconnect_attempt 메트릭이 증가한다`() {
        val received = ConcurrentLinkedQueue<String>()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                webSocket.close(1000, "bye")
            }
        }))
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                webSocket.send("""{"price":"200"}""")
                webSocket.close(1000, "done")
            }
        }))

        val config = WebSocketConnectionConfig(
            exchange = "test",
            url = wsUrl(),
            onMessage = { received.add(it) },
            firstMessageTimeout = Duration.ofSeconds(10),
        )
        manager = WebSocketConnectionManager(
            config, metrics, client,
            initialBackoff = Duration.ofMillis(50),
            maxBackoff = Duration.ofSeconds(1),
        ).also { it.start() }

        await().atMost(5, TimeUnit.SECONDS).untilAsserted {
            assertThat(received).contains("""{"price":"200"}""")
        }
        assertThat(registry.find("ws.reconnect.attempt").tag("exchange", "test").counter()?.count())
            .isGreaterThanOrEqualTo(1.0)
    }

    @Test
    fun `stop 후 start 시 펜딩 reconnect timer가 dispose되어 중복 연결이 생기지 않는다`() {
        val connections = AtomicInteger(0)
        repeat(4) {
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                    connections.incrementAndGet()
                    webSocket.close(1000, "bye")
                }
            }))
        }
        val config = WebSocketConnectionConfig(
            exchange = "test",
            url = wsUrl(),
            onMessage = { },
            firstMessageTimeout = Duration.ofSeconds(10),
        )
        manager = WebSocketConnectionManager(
            config, metrics, client,
            initialBackoff = Duration.ofMillis(500),
            maxBackoff = Duration.ofSeconds(2),
        ).also { it.start() }

        await().atMost(2, TimeUnit.SECONDS).until { connections.get() >= 1 }

        manager!!.stop()
        Thread.sleep(100)
        manager!!.start()

        Thread.sleep(700)
        assertThat(connections.get()).isLessThanOrEqualTo(3)
    }

    private fun wsUrl(): String {
        val httpUrl = server.url("/ws")
        return "ws://${httpUrl.host}:${httpUrl.port}/ws"
    }
}
