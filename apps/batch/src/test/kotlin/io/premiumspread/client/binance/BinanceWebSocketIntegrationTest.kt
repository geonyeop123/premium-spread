package io.premiumspread.client.binance

import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.premiumspread.cache.TickerCacheService
import io.premiumspread.infrastructure.ingestion.binance.BinanceTickerIngestion
import io.premiumspread.infrastructure.websocket.WebSocketConnectionConfig
import io.premiumspread.infrastructure.websocket.WebSocketConnectionManager
import io.premiumspread.infrastructure.websocket.WebSocketMetrics
import io.premiumspread.config.BatchTestConfig
import io.premiumspread.monitoring.AlertService
import io.premiumspread.testcontainers.MySqlTestContainersConfig
import io.premiumspread.testcontainers.RedisTestContainersConfig
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.kotlin.await
import org.awaitility.kotlin.matches
import org.awaitility.kotlin.untilCallTo
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue

@Tag("integration")
@SpringBootTest
@ActiveProfiles("test")
@Import(MySqlTestContainersConfig::class, RedisTestContainersConfig::class, BatchTestConfig::class)
class BinanceWebSocketIntegrationTest {

    @Autowired private lateinit var tickerCacheService: TickerCacheService

    private lateinit var server: MockWebServer
    private val metrics = WebSocketMetrics(SimpleMeterRegistry())
    private val captured = ConcurrentLinkedQueue<String>()
    private val fakeAlertService = object : AlertService {
        override fun sendAlert(message: String, severity: AlertService.Severity) {
            captured.add("[$severity] $message")
        }
    }

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun tearDown() {
        try {
            Thread.sleep(300)
            server.shutdown()
        } catch (e: Exception) {
            // ignore
        }
    }

    @Test
    fun `메시지 1건 push되면 TickerCacheService 캐시가 갱신된다`() {
        val payload = """{"e":"24hrMiniTicker","E":1715470800000,"s":"BTCUSDT","c":"89277.10"}"""
        server.enqueue(MockResponse().withWebSocketUpgrade(EchoOnce(payload)))

        val ingestion = BinanceTickerIngestion(tickerCacheService, metrics, fakeAlertService, java.time.Clock.systemUTC())
        val client = BinanceWebSocketClient(ingestion, metrics, fakeAlertService, ObjectMapper(), SimpleMeterRegistry())
        // direct manager so we can override URL to MockWebServer
        val manager = WebSocketConnectionManager(
            config = WebSocketConnectionConfig(
                exchange = "binance",
                url = server.url("/ws/btcusdt@miniTicker").toString().replace("http", "ws"),
                onMessage = client::handlePayload,
            ),
            metrics = metrics,
            alertService = fakeAlertService,
        )
        manager.start()

        await.atMost(Duration.ofSeconds(5)).untilCallTo {
            tickerCacheService.get("BINANCE", "BTC")
        } matches { it != null && it.price.toPlainString() == "89277.10" }

        manager.stop()
    }

    @Test
    fun `연결됐지만 첫 메시지 timeout이 지나면 AlertService에 critical alert가 호출된다`() {
        // upgrade만 하고 메시지 안 보내는 listener
        server.enqueue(MockResponse().withWebSocketUpgrade(IdleListener()))

        val ingestion = BinanceTickerIngestion(tickerCacheService, metrics, fakeAlertService, java.time.Clock.systemUTC())
        val client = BinanceWebSocketClient(ingestion, metrics, fakeAlertService, ObjectMapper(), SimpleMeterRegistry())
        val manager = WebSocketConnectionManager(
            config = WebSocketConnectionConfig(
                exchange = "binance",
                url = server.url("/ws/btcusdt@miniTicker").toString().replace("http", "ws"),
                firstMessageTimeout = Duration.ofSeconds(2),
                onMessage = client::handlePayload,
            ),
            metrics = metrics,
            alertService = fakeAlertService,
        )
        manager.start()

        await.atMost(Duration.ofSeconds(6)).untilCallTo { captured.toList() } matches { list ->
            list != null && list.any { msg -> msg.contains("[CRITICAL]") && msg.contains("binance") }
        }

        manager.stop()
    }
}

// 메시지 1건 송신 후 종료
private class EchoOnce(private val payload: String) : okhttp3.WebSocketListener() {
    override fun onOpen(webSocket: okhttp3.WebSocket, response: okhttp3.Response) {
        webSocket.send(payload)
        webSocket.close(1000, null)
    }
}

private class IdleListener : okhttp3.WebSocketListener()
