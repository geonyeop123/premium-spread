package io.premiumspread.client.binance

import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.mockk
import io.mockk.verify
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
    fun `정상 bookTicker payload는 mid 가격으로 파싱된다`() {
        val payload = """
            {"e":"bookTicker","u":400900217,"E":1715470800000,"T":1715470800000,"s":"BTCUSDT","b":"89277.00","B":"1.5","a":"89277.20","A":"2.0"}
        """.trimIndent()

        val ticker = client.parse(payload)

        assertThat(ticker).isNotNull
        assertThat(ticker!!.exchange).isEqualTo("BINANCE")
        assertThat(ticker.symbol).isEqualTo("BTC")
        assertThat(ticker.currency).isEqualTo("USD")
        // mid = (89277.00 + 89277.20) / 2 = 89277.10
        assertThat(ticker.price).isEqualByComparingTo(BigDecimal("89277.10"))
        assertThat(ticker.volume).isNull()
        assertThat(ticker.timestamp).isEqualTo(Instant.ofEpochMilli(1715470800000))
    }

    @Test
    fun `malformed JSON은 null을 반환한다`() {
        assertThat(client.parse("not-json")).isNull()
    }

    @Test
    fun `숫자가 아닌 bestBid는 null을 반환한다`() {
        val payload = """{"e":"bookTicker","u":1,"E":1,"T":1,"s":"BTCUSDT","b":"NaN","a":"89277.20"}"""
        assertThat(client.parse(payload)).isNull()
    }

    @Test
    fun `숫자가 아닌 bestAsk는 null을 반환한다`() {
        val payload = """{"e":"bookTicker","u":1,"E":1,"T":1,"s":"BTCUSDT","b":"89277.00","a":"NaN"}"""
        assertThat(client.parse(payload)).isNull()
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

        val counter = meterRegistry.find("ws.parse.error").tag("exchange", "binance").counter()
        assertThat(counter).isNotNull
        assertThat(counter!!.count()).isEqualTo(5.0)
        verify(exactly = 1) {
            alertService.sendCriticalAlert(match { it.contains("5회 연속") && it.contains("binance") })
        }
    }
}
