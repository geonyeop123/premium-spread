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
    private val capturedAlerts = ConcurrentLinkedQueue<String>()
    private val fakeAlertService = object : io.premiumspread.monitoring.AlertService {
        override fun sendAlert(message: String, severity: io.premiumspread.monitoring.AlertService.Severity) {
            capturedAlerts.add(message)
        }
    }

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
        } catch (e: Exception) {
            // sleep으로 reactor drain 후에도 shutdown timeout 발생 가능. 마스킹 방지 위해 로깅.
            org.slf4j.LoggerFactory.getLogger(javaClass).warn("MockWebServer shutdown failed", e)
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
        manager = WebSocketConnectionManager(config, metrics, client, fakeAlertService).also { it.start() }

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
            config, metrics, client, fakeAlertService,
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
            config, metrics, client, fakeAlertService,
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

    @Test
    fun `stop 호출 시 reconnect 메트릭이 증가하지 않는다 (Code review fix)`() {
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                webSocket.send("hello")
            }
        }))
        val config = WebSocketConnectionConfig(
            exchange = "test",
            url = wsUrl(),
            onMessage = { },
            firstMessageTimeout = Duration.ofSeconds(10),
        )
        manager = WebSocketConnectionManager(
            config, metrics, client, fakeAlertService,
            initialBackoff = Duration.ofMillis(500),
            maxBackoff = Duration.ofSeconds(2),
        ).also { it.start() }

        await().atMost(2, TimeUnit.SECONDS).until {
            (registry.find("ws.message.received").tag("exchange", "test").counter()?.count() ?: 0.0) >= 1.0
        }
        val before = registry.find("ws.reconnect.attempt").tag("exchange", "test").counter()?.count() ?: 0.0

        manager!!.stop()
        Thread.sleep(200) // doFinally가 fire될 시간 확보

        val after = registry.find("ws.reconnect.attempt").tag("exchange", "test").counter()?.count() ?: 0.0
        assertThat(after).isEqualTo(before) // stop으로 인한 CANCEL은 reconnect 메트릭 증가시키지 않아야 함
    }

    @Test
    fun `연결 후 첫 메시지가 timeout 내 도착하지 않으면 first_message_timeout 메트릭과 알람을 호출한다`() {
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {}))

        val config = WebSocketConnectionConfig(
            exchange = "test",
            url = wsUrl(),
            onMessage = { },
            firstMessageTimeout = Duration.ofMillis(300),
        )
        manager = WebSocketConnectionManager(config, metrics, client, fakeAlertService).also { it.start() }

        await().atMost(2, TimeUnit.SECONDS).untilAsserted {
            assertThat(registry.find("ws.first.message.timeout").tag("exchange", "test").counter()?.count() ?: 0.0)
                .isGreaterThanOrEqualTo(1.0)
            assertThat(capturedAlerts).isNotEmpty()
        }
    }

    @Test
    fun `메시지가 timeout 전에 도착하면 first_message_timeout 메트릭과 알람이 트리거되지 않는다`() {
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                webSocket.send("hello")
            }
        }))
        val config = WebSocketConnectionConfig(
            exchange = "test",
            url = wsUrl(),
            onMessage = { },
            firstMessageTimeout = Duration.ofMillis(500),
        )
        manager = WebSocketConnectionManager(config, metrics, client, fakeAlertService).also { it.start() }

        await().atMost(1, TimeUnit.SECONDS).until {
            (registry.find("ws.message.received").tag("exchange", "test").counter()?.count() ?: 0.0) >= 1.0
        }
        Thread.sleep(700)
        assertThat(registry.find("ws.first.message.timeout").tag("exchange", "test").counter()?.count() ?: 0.0).isZero
        assertThat(capturedAlerts).isEmpty()
    }

    @Test
    fun `서버가 즉시 끊으면 timeout 대기 없이 즉시 재연결을 시도한다`() {
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                webSocket.close(1000, "bye")
            }
        }))
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                webSocket.send("ok")
            }
        }))
        val config = WebSocketConnectionConfig(
            exchange = "test",
            url = wsUrl(),
            onMessage = { },
            firstMessageTimeout = Duration.ofSeconds(30),
        )
        manager = WebSocketConnectionManager(
            config, metrics, client, fakeAlertService,
            initialBackoff = Duration.ofMillis(100),
            maxBackoff = Duration.ofMillis(500),
        ).also { it.start() }

        await().atMost(2, TimeUnit.SECONDS).untilAsserted {
            assertThat(registry.find("ws.message.received").tag("exchange", "test").counter()?.count() ?: 0.0)
                .isGreaterThanOrEqualTo(1.0)
        }
    }

    @Test
    fun `ClientPing 정책 시 일정 주기로 ping 메시지를 송신한다`() {
        val pingsReceived = ConcurrentLinkedQueue<String>()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) { pingsReceived.add(text) }
        }))

        val config = WebSocketConnectionConfig(
            exchange = "test",
            url = wsUrl(),
            heartbeat = HeartbeatPolicy.ClientPing(interval = Duration.ofMillis(200), pingMessage = "ping"),
            firstMessageTimeout = Duration.ofSeconds(5),
            onMessage = { },
        )
        manager = WebSocketConnectionManager(config, metrics, client, fakeAlertService).also { it.start() }

        await().atMost(2, TimeUnit.SECONDS).untilAsserted {
            assertThat(pingsReceived.filter { it == "ping" }).hasSizeGreaterThanOrEqualTo(2)
        }
    }

    private fun wsUrl(): String {
        val httpUrl = server.url("/ws")
        return "ws://${httpUrl.host}:${httpUrl.port}/ws"
    }
}
