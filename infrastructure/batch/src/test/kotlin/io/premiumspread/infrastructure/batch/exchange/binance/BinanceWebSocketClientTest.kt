package io.premiumspread.infrastructure.batch.exchange.binance

import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.mockk
import io.premiumspread.domain.job.OperatorAlert
import io.premiumspread.infrastructure.batch.ingestion.binance.BinanceTickerIngestion
import io.premiumspread.infrastructure.batch.websocket.WebSocketMetrics
import io.premiumspread.infrastructure.batch.websocket.WebSocketStreamProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.net.URI
import java.time.Clock

class BinanceWebSocketClientTest {
    private val registry = SimpleMeterRegistry()
    private val properties = WebSocketStreamProperties(
        binance = WebSocketStreamProperties.Stream(
            URI.create("wss://example.test/ws/btcusdt@bookTicker"),
            "BTC",
            "USDT",
        ),
    )
    private val client = BinanceWebSocketClient(
        ingestion = mockk<BinanceTickerIngestion>(relaxed = true),
        metrics = WebSocketMetrics(registry, Clock.systemUTC()),
        operatorAlert = OperatorAlert { },
        objectMapper = ObjectMapper(),
        meterRegistry = registry,
        properties = properties,
    )

    @Test
    fun `configured Binance pair is parsed with raw USDT quote`() {
        val ticker = client.parse(payload("BTCUSDT"))

        assertThat(ticker).isNotNull
        assertThat(ticker!!.symbol).isEqualTo("BTC")
        assertThat(ticker.currency).isEqualTo("USDT")
        assertThat(ticker.toMarketTick().quote.currency.code).isEqualTo("USD")
    }

    @Test
    fun `payload pair mismatch is rejected instead of relabelled`() {
        assertThat(client.parse(payload("ETHUSDT"))).isNull()
        assertThat(registry.find("ws.parse.error").tag("exchange", "binance").counter()?.count())
            .isEqualTo(1.0)
    }

    private fun payload(symbol: String) =
        """{"E":1715470800000,"s":"$symbol","b":"100.0","a":"102.0"}"""
}
