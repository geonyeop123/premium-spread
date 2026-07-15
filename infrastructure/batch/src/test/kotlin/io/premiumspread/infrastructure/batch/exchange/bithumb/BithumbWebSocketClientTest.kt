package io.premiumspread.infrastructure.batch.exchange.bithumb

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.mockk
import io.premiumspread.domain.job.OperatorAlert
import io.premiumspread.domain.job.OperatorAlertMessage
import io.premiumspread.infrastructure.batch.ingestion.bithumb.BithumbTickerIngestion
import io.premiumspread.infrastructure.batch.websocket.WebSocketMetrics
import io.premiumspread.infrastructure.batch.websocket.WebSocketStreamProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue

class BithumbWebSocketClientTest {
    private val registry = SimpleMeterRegistry()
    private val alerts = ConcurrentLinkedQueue<OperatorAlertMessage>()
    private val client = BithumbWebSocketClient(
        ingestion = mockk<BithumbTickerIngestion>(relaxed = true),
        metrics = WebSocketMetrics(registry, Clock.systemUTC()),
        operatorAlert = OperatorAlert(alerts::add),
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
    fun `ticker payload is parsed with configured pair and exchange timestamp`() {
        val ticker = client.parse(
            payload(
                date = "20260512",
                time = "093000",
                closePrice = "100000000",
                volume = "1.5",
            ),
        )

        assertThat(ticker).isNotNull
        assertThat(ticker!!.exchange).isEqualTo("BITHUMB")
        assertThat(ticker.symbol).isEqualTo("BTC")
        assertThat(ticker.currency).isEqualTo("KRW")
        assertThat(ticker.price).isEqualByComparingTo(BigDecimal("100000000"))
        assertThat(ticker.volume).isEqualByComparingTo(BigDecimal("1.5"))
        assertThat(ticker.timestamp).isEqualTo(Instant.parse("2026-05-12T00:30:00Z"))
    }

    @Test
    fun `non ticker acknowledgement is ignored without parse failure`() {
        assertThat(client.parse("""{"type":"status","resmsg":"Connected Successfully"}""")).isNull()
        assertThat(parseErrorCount()).isZero()
    }

    @Test
    fun `missing or malformed exchange timestamp is rejected instead of synthesized`() {
        assertThat(client.parse(payload(date = null, time = "093000"))).isNull()
        assertThat(client.parse(payload(date = "20260512", time = "not-time"))).isNull()

        assertThat(parseErrorCount()).isEqualTo(2.0)
    }

    @Test
    fun `payload pair mismatch is rejected instead of relabelled`() {
        assertThat(client.parse(payload(symbol = "ETH_KRW"))).isNull()
        assertThat(parseErrorCount()).isEqualTo(1.0)
    }

    @Test
    fun `five consecutive malformed payloads emit one bounded operator alert`() {
        repeat(5) { client.handlePayload(payload(closePrice = "invalid")) }

        assertThat(alerts).hasSize(1)
        val alert = alerts.single()
        assertThat(alert.code).isEqualTo("websocket.parse.failure")
        assertThat(alert.message).contains("5회 연속")
        assertThat(alert.attributes).containsEntry("exchange", "bithumb")
        assertThat(parseErrorCount()).isEqualTo(5.0)
    }

    private fun payload(
        symbol: String = "BTC_KRW",
        date: String? = "20260512",
        time: String? = "093000",
        closePrice: String = "100000000",
        volume: String? = null,
    ): String {
        val dateField = date?.let { "\"date\":\"$it\"," } ?: ""
        val timeField = time?.let { "\"time\":\"$it\"," } ?: ""
        val volumeField = volume?.let { ",\"volume\":\"$it\"" } ?: ""
        return """{"type":"ticker","content":{"symbol":"$symbol","tickType":"24H",$dateField$timeField"closePrice":"$closePrice"$volumeField}}"""
    }

    private fun parseErrorCount(): Double =
        registry.find("ws.parse.error").tag("exchange", "bithumb").counter()?.count() ?: 0.0
}
