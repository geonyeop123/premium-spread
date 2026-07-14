package io.premiumspread.infrastructure.batch

import io.premiumspread.infrastructure.batch.websocket.WebSocketStreamProperties
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.net.URI

class BatchMarketStreamConsistencyValidatorTest {
    @Test
    fun `endpoint pair and configured symbol cannot diverge`() {
        val streams = WebSocketStreamProperties(
            binance = WebSocketStreamProperties.Stream(
                URI.create("wss://example.test/ws/btcusdt@bookTicker"),
                "ETH",
                "USDT",
            ),
        )

        assertThatThrownBy { BatchMarketStreamConsistencyValidator(BatchMarketProperties(symbol = "ETH"), streams) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
