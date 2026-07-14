package io.premiumspread.client.bithumb

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.premiumspread.cache.TickerCacheService
import io.premiumspread.infrastructure.ingestion.bithumb.BithumbFlushJob
import io.premiumspread.infrastructure.ingestion.bithumb.BithumbTickerIngestion
import io.premiumspread.infrastructure.websocket.WebSocketConnectionConfig
import io.premiumspread.infrastructure.websocket.WebSocketConnectionManager
import io.premiumspread.infrastructure.websocket.WebSocketMetrics
import io.premiumspread.monitoring.AlertService
import io.premiumspread.support.BatchIntegrationTestBase
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 빗썸 WebSocket 통합 시나리오 검증.
 *
 * - MockWebServer로 WebSocket upgrade 후 ticker payload 송신
 * - TickerCacheService + Redis Testcontainer로 hash/ZSet 검증
 */
class BithumbWebSocketIntegrationTest : BatchIntegrationTestBase() {

    @Autowired
    private lateinit var tickerCacheService: TickerCacheService

    private lateinit var server: MockWebServer
    private val metrics = WebSocketMetrics(SimpleMeterRegistry(), Clock.systemUTC())
    private val alerts = ConcurrentLinkedQueue<String>()
    private val fakeAlertService = object : AlertService {
        override fun sendAlert(message: String, severity: AlertService.Severity) {
            alerts.add("[$severity] $message")
        }
    }
    private val objectMapper = ObjectMapper().apply { registerModule(kotlinModule()) }

    @BeforeEach
    fun setUpServer() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun tearDownServer() {
        try {
            Thread.sleep(300)
            server.shutdown()
        } catch (e: Exception) {
            // ignore
        }
    }

    @Test
    fun `메시지 1건 push되면 hash가 갱신되고 flush가 ZSet에 score 1개를 저장한다`() {
        val payload = """
            {"type":"ticker","content":{"symbol":"BTC_KRW","tickType":"24H","date":"20260512","time":"093000","closePrice":"100000000","volume":"1.5"}}
        """.trimIndent()
        server.enqueue(MockResponse().withWebSocketUpgrade(EchoOnce(payload)))

        val ingestion = BithumbTickerIngestion(tickerCacheService, metrics, fakeAlertService, Clock.systemUTC())
        val flushJob = BithumbFlushJob(ingestion, tickerCacheService, metrics, fakeAlertService, redisTemplate, Clock.systemUTC())
        val client = BithumbWebSocketClient(ingestion, metrics, fakeAlertService, objectMapper, SimpleMeterRegistry())
        val manager = WebSocketConnectionManager(
            config = WebSocketConnectionConfig(
                exchange = "bithumb",
                url = server.url("/pub/ws").toString().replace("http", "ws"),
                onMessage = client::handlePayload,
            ),
            metrics = metrics,
            alertService = fakeAlertService,
        )
        manager.start()

        try {
            await().atMost(Duration.ofSeconds(5)).until {
                val cached = tickerCacheService.get("BITHUMB", "BTC")
                cached != null && cached.price.toPlainString() == "100000000"
            }

            // 동일 가격으로 5회 flush — 멤버 `{epochMs}:{price}` 포맷이라 distinct entries로 누적 (flat-price 회귀 방지)
            repeat(5) {
                flushJob.run()
                Thread.sleep(50)
            }

            val results = tickerCacheService.getSecondsData(
                "BITHUMB", "BTC",
                from = Instant.now().minusSeconds(60),
                to = Instant.now().plusSeconds(60),
            )
            assertThat(results.map { it.first }).hasSize(5)
            assertThat(results).allSatisfy { (_, p) ->
                assertThat(p).isEqualByComparingTo(BigDecimal("100000000"))
            }
            assertThat(redisTemplate.opsForValue().get(BithumbFlushJob.LAST_RUN_KEY)).isNotBlank
        } finally {
            manager.stop()
        }
    }

    @Test
    fun `연결됐지만 5초 동안 메시지 zero면 critical alert가 호출된다 (silent outage 회귀 방지)`() {
        server.enqueue(MockResponse().withWebSocketUpgrade(IdleListener()))

        val ingestion = BithumbTickerIngestion(tickerCacheService, metrics, fakeAlertService, Clock.systemUTC())
        val client = BithumbWebSocketClient(ingestion, metrics, fakeAlertService, objectMapper, SimpleMeterRegistry())
        val manager = WebSocketConnectionManager(
            config = WebSocketConnectionConfig(
                exchange = "bithumb",
                url = server.url("/pub/ws").toString().replace("http", "ws"),
                firstMessageTimeout = Duration.ofSeconds(2),
                onMessage = client::handlePayload,
            ),
            metrics = metrics,
            alertService = fakeAlertService,
        )
        manager.start()

        try {
            await().atMost(Duration.ofSeconds(6)).until {
                alerts.any { msg -> msg.contains("[CRITICAL]") && msg.contains("bithumb") }
            }
        } finally {
            manager.stop()
        }
    }

    @Test
    fun `reverse-order 메시지 시퀀스에서 오래된 메시지는 폐기된다 (reorder 회귀 방지)`() {
        val newer = """{"type":"ticker","content":{"symbol":"BTC_KRW","tickType":"24H","date":"20260512","time":"093005","closePrice":"100000000"}}"""
        val older = """{"type":"ticker","content":{"symbol":"BTC_KRW","tickType":"24H","date":"20260512","time":"093000","closePrice":"99000000"}}"""
        server.enqueue(MockResponse().withWebSocketUpgrade(SendBoth(newer, older)))

        val ingestion = BithumbTickerIngestion(tickerCacheService, metrics, fakeAlertService, Clock.systemUTC())
        val client = BithumbWebSocketClient(ingestion, metrics, fakeAlertService, objectMapper, SimpleMeterRegistry())
        val manager = WebSocketConnectionManager(
            config = WebSocketConnectionConfig(
                exchange = "bithumb",
                url = server.url("/pub/ws").toString().replace("http", "ws"),
                onMessage = client::handlePayload,
            ),
            metrics = metrics,
            alertService = fakeAlertService,
        )
        manager.start()

        try {
            // 둘 다 도착할 시간을 충분히 줌
            await().atMost(Duration.ofSeconds(5)).until {
                ingestion.latest()?.ticker?.price?.toPlainString() == "100000000"
            }

            // older가 도착해도 latest는 100M (newer) 그대로
            Thread.sleep(500)
            assertThat(ingestion.latest()?.ticker?.price?.toPlainString()).isEqualTo("100000000")
        } finally {
            manager.stop()
        }
    }
}

private class EchoOnce(private val payload: String) : okhttp3.WebSocketListener() {
    override fun onOpen(webSocket: okhttp3.WebSocket, response: okhttp3.Response) {
        webSocket.send(payload)
    }
}

private class IdleListener : okhttp3.WebSocketListener()

private class SendBoth(private val first: String, private val second: String) : okhttp3.WebSocketListener() {
    override fun onOpen(webSocket: okhttp3.WebSocket, response: okhttp3.Response) {
        webSocket.send(first)
        Thread.sleep(100)
        webSocket.send(second)
    }
}
