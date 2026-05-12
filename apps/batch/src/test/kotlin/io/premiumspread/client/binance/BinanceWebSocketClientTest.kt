package io.premiumspread.client.binance

import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.mockk
import io.premiumspread.infrastructure.ingestion.binance.BinanceTickerIngestion
import io.premiumspread.infrastructure.websocket.WebSocketMetrics
import io.premiumspread.monitoring.AlertService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

class BinanceWebSocketClientTest {

    private val client = BinanceWebSocketClient(
        ingestion = mockk(relaxed = true),
        metrics = mockk(relaxed = true),
        alertService = mockk(relaxed = true),
        objectMapper = ObjectMapper(),
        meterRegistry = SimpleMeterRegistry(),
    )

    @Test
    fun `정상 miniTicker payload는 TickerData로 파싱된다`() {
        val payload = """
            {"e":"24hrMiniTicker","E":1715470800000,"s":"BTCUSDT","c":"89277.10","o":"88042.60","h":"90000.00","l":"87500.00","v":"123.45","q":"1000000"}
        """.trimIndent()

        val ticker = client.parse(payload)

        assertThat(ticker).isNotNull
        assertThat(ticker!!.exchange).isEqualTo("BINANCE")
        assertThat(ticker.symbol).isEqualTo("BTC")
        assertThat(ticker.currency).isEqualTo("USD")
        assertThat(ticker.price).isEqualByComparingTo(BigDecimal("89277.10"))
        assertThat(ticker.volume).isEqualByComparingTo(BigDecimal("123.45"))
        assertThat(ticker.timestamp).isEqualTo(Instant.ofEpochMilli(1715470800000))
    }

    @Test
    fun `malformed JSON은 null을 반환한다`() {
        val ticker = client.parse("not-json")
        assertThat(ticker).isNull()
    }

    @Test
    fun `숫자가 아닌 price는 null을 반환한다`() {
        val payload = """{"e":"24hrMiniTicker","E":1,"s":"BTCUSDT","c":"NaN"}"""
        val ticker = client.parse(payload)
        assertThat(ticker).isNull()
    }
}
