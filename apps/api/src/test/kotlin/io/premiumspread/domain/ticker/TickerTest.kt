package io.premiumspread.domain.ticker

import java.math.BigDecimal
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class TickerTest {
    @Test
    fun `티커 가격이 0 이하이면 예외를 던진다`() {
        val prices = listOf("0", "-1")

        prices.forEach { price ->
            assertThrows(InvalidTickerException::class.java) {
                Ticker.create(
                    exchange = Exchange.UPBIT,
                    quote = Quote.coin(Symbol("BTC"), Currency.KRW),
                    price = BigDecimal(price),
                    observedAt = Instant.parse("2024-01-01T00:00:00Z"),
                )
            }
        }
    }
}
