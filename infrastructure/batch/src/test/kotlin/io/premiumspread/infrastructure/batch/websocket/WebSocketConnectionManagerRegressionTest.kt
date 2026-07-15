package io.premiumspread.infrastructure.batch.websocket

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.premiumspread.domain.job.OperatorAlert
import io.premiumspread.domain.job.OperatorAlertMessage
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class WebSocketConnectionManagerRegressionTest {
    private lateinit var server: MockWebServer
    private lateinit var registry: SimpleMeterRegistry
    private lateinit var metrics: WebSocketMetrics
    private lateinit var alerts: ConcurrentLinkedQueue<OperatorAlertMessage>
    private lateinit var serverSockets: ConcurrentLinkedQueue<WebSocket>
    private lateinit var operatorAlert: OperatorAlert
    private var manager: WebSocketConnectionManager? = null

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        registry = SimpleMeterRegistry()
        metrics = WebSocketMetrics(registry, Clock.systemUTC())
        alerts = ConcurrentLinkedQueue()
        serverSockets = ConcurrentLinkedQueue()
        operatorAlert = OperatorAlert(alerts::add)
    }

    @AfterEach
    fun tearDown() {
        manager?.stop()
        serverSockets.forEach { socket -> socket.close(1000, "test complete") }
        server.shutdown()
        registry.close()
    }

    @Test
    fun `received frame is delivered and observed`() {
        val received = ConcurrentLinkedQueue<String>()
        server.enqueue(webSocketResponse { webSocket -> webSocket.send("ticker") })
        manager = manager(
            firstMessageTimeout = Duration.ofSeconds(2),
            idleTimeout = Duration.ofSeconds(5),
            onMessage = received::add,
        ).also { it.start() }

        // CI에서 전체 모듈 테스트가 병렬로 실행되면 WebSocket 핸드셰이크가 늦어질 수 있다.
        // 메시지 개수 조건은 유지하되 관찰 창만 넉넉히 잡아 시간 의존적인 실패를 피한다.
        await().atMost(Duration.ofSeconds(5)).untilAsserted {
            assertThat(received).contains("ticker")
            assertThat(messageCount()).isEqualTo(1.0)
            assertThat(connectionState()).isEqualTo(1.0)
        }
    }

    @Test
    fun `server disconnect reconnects and delivers the next frame`() {
        val received = ConcurrentLinkedQueue<String>()
        server.enqueue(webSocketResponse { webSocket -> webSocket.close(1000, "bye") })
        server.enqueue(webSocketResponse { webSocket -> webSocket.send("after-reconnect") })
        manager = manager(
            firstMessageTimeout = Duration.ofSeconds(10),
            idleTimeout = Duration.ofSeconds(20),
            onMessage = received::add,
        ).also { it.start() }

        await().atMost(Duration.ofSeconds(4)).untilAsserted {
            assertThat(received).contains("after-reconnect")
            assertThat(reconnectCount()).isGreaterThanOrEqualTo(1.0)
        }
    }

    @Test
    fun `stop then start does not retain a duplicate reconnect timer`() {
        val connections = AtomicInteger()
        repeat(5) {
            server.enqueue(webSocketResponse { webSocket ->
                connections.incrementAndGet()
                webSocket.close(1000, "bye")
            })
        }
        val candidate = manager(
            firstMessageTimeout = Duration.ofSeconds(10),
            idleTimeout = Duration.ofSeconds(20),
            initialBackoff = Duration.ofMillis(500),
            maxBackoff = Duration.ofSeconds(2),
            onMessage = {},
        )
        manager = candidate
        candidate.start()
        await().atMost(Duration.ofSeconds(2)).until { connections.get() == 1 }

        candidate.stop()
        Thread.sleep(100)
        candidate.start()
        Thread.sleep(700)

        assertThat(connections.get()).isLessThanOrEqualTo(3)
    }

    @Test
    fun `stop does not increment reconnect attempts`() {
        server.enqueue(webSocketResponse { webSocket -> webSocket.send("hello") })
        val candidate = manager(
            firstMessageTimeout = Duration.ofSeconds(10),
            idleTimeout = Duration.ofSeconds(20),
            initialBackoff = Duration.ofMillis(500),
            onMessage = {},
        )
        manager = candidate
        candidate.start()
        await().atMost(Duration.ofSeconds(2)).until { messageCount() >= 1.0 }
        val before = reconnectCount()

        candidate.stop()
        Thread.sleep(250)

        assertThat(reconnectCount()).isEqualTo(before)
    }

    @Test
    fun `first message timeout alerts and reconnects`() {
        server.enqueue(webSocketResponse())
        server.enqueue(webSocketResponse { webSocket -> webSocket.send("after-timeout") })
        val received = ConcurrentLinkedQueue<String>()
        manager = manager(
            firstMessageTimeout = Duration.ofMillis(150),
            idleTimeout = Duration.ofSeconds(5),
            onMessage = received::add,
        ).also { it.start() }

        await().atMost(Duration.ofSeconds(3)).untilAsserted {
            assertThat(received).contains("after-timeout")
            assertThat(firstMessageTimeoutCount()).isGreaterThanOrEqualTo(1.0)
            assertThat(reconnectCount()).isGreaterThanOrEqualTo(1.0)
            assertThat(alerts).anyMatch { it.message.contains("미수신") }
        }
    }

    @Test
    fun `first frame before timeout cancels timeout alert`() {
        server.enqueue(webSocketResponse { webSocket -> webSocket.send("hello") })
        manager = manager(
            firstMessageTimeout = Duration.ofMillis(300),
            idleTimeout = Duration.ofSeconds(5),
            onMessage = {},
        ).also { it.start() }

        await().atMost(Duration.ofSeconds(2)).until { messageCount() >= 1.0 }
        Thread.sleep(500)

        assertThat(firstMessageTimeoutCount()).isZero()
        assertThat(alerts).isEmpty()
    }

    @Test
    fun `immediate disconnect reconnects without waiting for first message timeout`() {
        server.enqueue(webSocketResponse { webSocket -> webSocket.close(1000, "bye") })
        server.enqueue(webSocketResponse { webSocket -> webSocket.send("ok") })
        manager = manager(
            firstMessageTimeout = Duration.ofSeconds(30),
            idleTimeout = Duration.ofSeconds(60),
            initialBackoff = Duration.ofMillis(75),
            maxBackoff = Duration.ofMillis(300),
            onMessage = {},
        ).also { it.start() }

        await().atMost(Duration.ofSeconds(2)).untilAsserted {
            assertThat(messageCount()).isGreaterThanOrEqualTo(1.0)
            assertThat(firstMessageTimeoutCount()).isZero()
        }
    }

    @Test
    fun `client ping heartbeat is emitted while connected`() {
        val pings = ConcurrentLinkedQueue<String>()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                serverSockets.add(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                pings.add(text)
            }
        }))
        manager = manager(
            firstMessageTimeout = Duration.ofSeconds(2),
            idleTimeout = Duration.ofSeconds(5),
            heartbeat = HeartbeatPolicy.ClientPing(Duration.ofMillis(40), "ping"),
            onMessage = {},
        ).also { it.start() }

        await().atMost(Duration.ofSeconds(2)).untilAsserted {
            assertThat(pings.count { it == "ping" }).isGreaterThanOrEqualTo(2)
        }
    }

    @Test
    fun `repeated handshake close applies exponential reconnect backoff`() {
        val openedAt = ConcurrentLinkedQueue<Long>()
        repeat(5) {
            server.enqueue(webSocketResponse { webSocket ->
                openedAt.add(System.nanoTime())
                webSocket.close(1000, "bye")
            })
        }
        manager = manager(
            firstMessageTimeout = Duration.ofSeconds(30),
            idleTimeout = Duration.ofSeconds(60),
            initialBackoff = Duration.ofMillis(80),
            maxBackoff = Duration.ofMillis(320),
            onMessage = {},
        ).also { it.start() }

        await().atMost(Duration.ofSeconds(4)).until { openedAt.size >= 4 }
        val timestamps = openedAt.toList()
        val gapsMs = timestamps.zipWithNext { before, after ->
            TimeUnit.NANOSECONDS.toMillis(after - before)
        }

        assertThat(gapsMs[0]).isGreaterThanOrEqualTo(50)
        assertThat(gapsMs[1]).isGreaterThanOrEqualTo(120)
        assertThat(gapsMs[2]).isGreaterThanOrEqualTo(240)
    }

    @Test
    fun `idle watchdog reconnects after a first frame goes silent`() {
        val received = ConcurrentLinkedQueue<String>()
        server.enqueue(webSocketResponse { webSocket -> webSocket.send("first") })
        server.enqueue(webSocketResponse { webSocket -> webSocket.send("second") })
        manager = manager(
            firstMessageTimeout = Duration.ofSeconds(2),
            idleTimeout = Duration.ofMillis(180),
            watchdogCheckInterval = Duration.ofMillis(40),
            onMessage = received::add,
        ).also { it.start() }

        await().atMost(Duration.ofSeconds(3)).untilAsserted {
            assertThat(received).contains("first", "second")
            assertThat(alerts).anyMatch { it.message.contains("idle") }
            assertThat(reconnectCount()).isGreaterThanOrEqualTo(1.0)
        }
    }

    @Test
    fun `continuous messages below idle timeout do not trigger watchdog`() {
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                serverSockets.add(webSocket)
                Thread {
                    repeat(10) { index ->
                        Thread.sleep(60)
                        if (!webSocket.send("frame-$index")) return@Thread
                    }
                }.start()
            }
        }))
        manager = manager(
            firstMessageTimeout = Duration.ofSeconds(2),
            idleTimeout = Duration.ofMillis(200),
            watchdogCheckInterval = Duration.ofMillis(40),
            onMessage = {},
        ).also { it.start() }

        await().atMost(Duration.ofSeconds(2)).until { messageCount() >= 7.0 }

        assertThat(alerts).noneMatch { it.message.contains("idle") }
        assertThat(reconnectCount()).isZero()
    }

    @Test
    fun `stale generation watchdog and timeout cannot terminate replacement connection`() {
        server.enqueue(webSocketResponse())
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                serverSockets.add(webSocket)
                Thread {
                    repeat(20) { index ->
                        Thread.sleep(60)
                        if (!webSocket.send("replacement-$index")) return@Thread
                    }
                }.start()
            }
        }))
        manager = manager(
            firstMessageTimeout = Duration.ofMillis(140),
            idleTimeout = Duration.ofSeconds(5),
            initialBackoff = Duration.ofMillis(60),
            maxBackoff = Duration.ofMillis(250),
            watchdogCheckInterval = Duration.ofMillis(25),
            onMessage = {},
        ).also { it.start() }

        await().atMost(Duration.ofSeconds(3)).untilAsserted {
            assertThat(reconnectCount()).isGreaterThanOrEqualTo(1.0)
            assertThat(messageCount()).isGreaterThanOrEqualTo(3.0)
        }
        val reconnectsBefore = reconnectCount()
        Thread.sleep(700)

        assertThat(reconnectCount()).isEqualTo(reconnectsBefore)
        assertThat(connectionState()).isEqualTo(1.0)
    }

    @Test
    fun `hanging alert delivery does not block timeout reconnect`() {
        val alertEntered = CountDownLatch(1)
        val alertGate = CountDownLatch(1)
        val hangingAlert = OperatorAlert {
            alertEntered.countDown()
            alertGate.await()
        }
        server.enqueue(webSocketResponse())
        server.enqueue(webSocketResponse { webSocket -> webSocket.send("after-hang") })
        val received = ConcurrentLinkedQueue<String>()
        val candidate = manager(
            firstMessageTimeout = Duration.ofMillis(140),
            idleTimeout = Duration.ofSeconds(5),
            initialBackoff = Duration.ofMillis(60),
            operatorAlert = hangingAlert,
            onMessage = received::add,
        )
        manager = candidate
        candidate.start()

        try {
            assertThat(alertEntered.await(2, TimeUnit.SECONDS)).isTrue()
            await().atMost(Duration.ofSeconds(3)).untilAsserted {
                assertThat(received).contains("after-hang")
            }
        } finally {
            alertGate.countDown()
        }
    }

    @Test
    fun `stop prevents pending reconnect and watchdog alert`() {
        val opens = AtomicInteger()
        repeat(2) {
            server.enqueue(webSocketResponse { webSocket ->
                opens.incrementAndGet()
                webSocket.send("hello")
            })
        }
        val candidate = manager(
            firstMessageTimeout = Duration.ofSeconds(2),
            idleTimeout = Duration.ofMillis(180),
            initialBackoff = Duration.ofMillis(500),
            maxBackoff = Duration.ofMillis(500),
            watchdogCheckInterval = Duration.ofMillis(40),
            onMessage = {},
        )
        manager = candidate
        candidate.start()
        await().atMost(Duration.ofSeconds(2)).until { messageCount() >= 1.0 }

        candidate.stop()
        Thread.sleep(650)

        assertThat(opens.get()).isEqualTo(1)
        assertThat(alerts).noneMatch { it.message.contains("idle") }
        assertThat(reconnectCount()).isZero()
    }

    private fun manager(
        firstMessageTimeout: Duration,
        idleTimeout: Duration,
        heartbeat: HeartbeatPolicy = HeartbeatPolicy.ServerPingResponse,
        initialBackoff: Duration = Duration.ofMillis(25),
        maxBackoff: Duration = Duration.ofMillis(100),
        watchdogCheckInterval: Duration = Duration.ofMillis(25),
        operatorAlert: OperatorAlert = this.operatorAlert,
        onMessage: (String) -> Unit,
    ) = WebSocketConnectionManager(
        config = WebSocketConnectionConfig(
            exchange = "test",
            url = wsUrl(),
            firstMessageTimeout = firstMessageTimeout,
            idleTimeout = idleTimeout,
            heartbeat = heartbeat,
            onMessage = onMessage,
        ),
        metrics = metrics,
        operatorAlert = operatorAlert,
        initialBackoff = initialBackoff,
        maxBackoff = maxBackoff,
        watchdogCheckInterval = watchdogCheckInterval,
    )

    private fun webSocketResponse(onOpen: ((WebSocket) -> Unit)? = null): MockResponse =
        MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                serverSockets.add(webSocket)
                onOpen?.invoke(webSocket)
            }
        })

    private fun wsUrl(): String = server.url("/ws").toString().replaceFirst("http", "ws")

    private fun connectionState(): Double =
        registry.find("ws.connection.state").tag("exchange", "test").gauge()?.value() ?: 0.0

    private fun messageCount(): Double =
        registry.find("ws.message.received").tag("exchange", "test").counter()?.count() ?: 0.0

    private fun reconnectCount(): Double =
        registry.find("ws.reconnect.attempt").tag("exchange", "test").counter()?.count() ?: 0.0

    private fun firstMessageTimeoutCount(): Double =
        registry.find("ws.first.message.timeout").tag("exchange", "test").counter()?.count() ?: 0.0
}
