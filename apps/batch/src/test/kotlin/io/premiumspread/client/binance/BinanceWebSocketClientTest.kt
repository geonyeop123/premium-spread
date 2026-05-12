package io.premiumspread.client.binance

import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.mockk
import io.mockk.verify
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

    @Test
    fun `parse 5회 연속 실패면 ws_parse_error 카운터 누적 + critical alert가 호출되고 counter는 리셋된다`() {
        val alertService = mockk<AlertService>(relaxed = true)
        val meterRegistry = SimpleMeterRegistry()
        val client = BinanceWebSocketClient(
            ingestion = mockk(relaxed = true),
            metrics = mockk(relaxed = true),
            alertService = alertService,
            objectMapper = ObjectMapper(),
            meterRegistry = meterRegistry,
        )

        repeat(5) { client.parse("not-json") }

        // ws.parse.error{exchange=binance} 누적 검증
        val counter = meterRegistry.find("ws.parse.error").tag("exchange", "binance").counter()
        assertThat(counter).isNotNull
        assertThat(counter!!.count()).isEqualTo(5.0)
        // 5회 연속에서 critical alert 1회 호출
        verify(exactly = 1) {
            alertService.sendCriticalAlert(match { it.contains("5회 연속") && it.contains("binance") })
        }
    }
}
