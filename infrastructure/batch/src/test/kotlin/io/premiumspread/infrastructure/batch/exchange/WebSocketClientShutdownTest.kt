package io.premiumspread.infrastructure.batch.exchange

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.mockk
import io.mockk.verify
import io.premiumspread.domain.job.OperatorAlert
import io.premiumspread.infrastructure.batch.exchange.binance.BinanceWebSocketClient
import io.premiumspread.infrastructure.batch.exchange.bithumb.BithumbWebSocketClient
import io.premiumspread.infrastructure.batch.ingestion.binance.BinanceTickerIngestion
import io.premiumspread.infrastructure.batch.ingestion.bithumb.BithumbTickerIngestion
import io.premiumspread.infrastructure.batch.websocket.WebSocketMetrics
import io.premiumspread.infrastructure.batch.websocket.WebSocketStreamProperties
import org.junit.jupiter.api.Test
import org.springframework.context.event.ContextClosedEvent
import org.springframework.context.support.GenericApplicationContext
import java.net.URI
import java.time.Clock

class WebSocketClientShutdownTest {
    private val registry = SimpleMeterRegistry()
    private val metrics = WebSocketMetrics(registry, Clock.systemUTC())
    private val closedEvent = ContextClosedEvent(GenericApplicationContext())

    private val binanceIngestion = mockk<BinanceTickerIngestion>(relaxed = true)
    private val binance = BinanceWebSocketClient(
        ingestion = binanceIngestion,
        metrics = metrics,
        operatorAlert = OperatorAlert { },
        objectMapper = ObjectMapper(),
        meterRegistry = registry,
        properties = WebSocketStreamProperties(
            binance = WebSocketStreamProperties.Stream(
                URI.create("wss://example.test/ws/btcusdt@bookTicker"),
                "BTC",
                "USDT",
            ),
        ),
    )

    private val bithumbIngestion = mockk<BithumbTickerIngestion>(relaxed = true)
    private val bithumb = BithumbWebSocketClient(
        ingestion = bithumbIngestion,
        metrics = metrics,
        operatorAlert = OperatorAlert { },
        objectMapper = ObjectMapper().registerKotlinModule(),
        meterRegistry = registry,
        properties = WebSocketStreamProperties(
            bithumb = WebSocketStreamProperties.Stream(
                endpoint = URI.create("wss://example.test/pub/ws"),
                symbol = "BTC",
                quote = "KRW",
            ),
        ),
    )

    @Test
    fun `바이낸스 클라이언트는 종료 신호 전 payload 를 ingestion 에 넘긴다`() {
        binance.handlePayload(BINANCE_PAYLOAD)

        verify(exactly = 1) { binanceIngestion.onMessage(any()) }
    }

    @Test
    fun `바이낸스 클라이언트는 종료 신호 뒤 payload 를 넘기지 않는다`() {
        binance.onApplicationEvent(closedEvent)

        binance.handlePayload(BINANCE_PAYLOAD)

        verify(exactly = 0) { binanceIngestion.onMessage(any()) }
    }

    @Test
    fun `바이낸스 클라이언트는 종료 신호 뒤 stop 을 다시 불러도 payload 를 넘기지 않는다`() {
        binance.onApplicationEvent(closedEvent)
        binance.stop()

        binance.handlePayload(BINANCE_PAYLOAD)

        verify(exactly = 0) { binanceIngestion.onMessage(any()) }
    }

    @Test
    fun `빗썸 클라이언트는 종료 신호 전 payload 를 ingestion 에 넘긴다`() {
        bithumb.handlePayload(BITHUMB_PAYLOAD)

        verify(exactly = 1) { bithumbIngestion.onMessage(any()) }
    }

    @Test
    fun `빗썸 클라이언트는 종료 신호 뒤 payload 를 넘기지 않는다`() {
        bithumb.onApplicationEvent(closedEvent)

        bithumb.handlePayload(BITHUMB_PAYLOAD)

        verify(exactly = 0) { bithumbIngestion.onMessage(any()) }
    }

    @Test
    fun `빗썸 클라이언트는 종료 신호 뒤 stop 을 다시 불러도 payload 를 넘기지 않는다`() {
        bithumb.onApplicationEvent(closedEvent)
        bithumb.stop()

        bithumb.handlePayload(BITHUMB_PAYLOAD)

        verify(exactly = 0) { bithumbIngestion.onMessage(any()) }
    }

    private companion object {
        const val BINANCE_PAYLOAD = """{"E":1715470800000,"s":"BTCUSDT","b":"100.0","a":"102.0"}"""
        const val BITHUMB_PAYLOAD =
            """{"type":"ticker","content":{"symbol":"BTC_KRW","tickType":"24H","date":"20260512","time":"093000","closePrice":"100000000"}}"""
    }
}
