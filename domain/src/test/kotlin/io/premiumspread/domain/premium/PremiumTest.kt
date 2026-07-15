package io.premiumspread.domain.premium

import io.premiumspread.domain.InvalidPremiumInputException
import io.premiumspread.domain.ticker.Currency
import io.premiumspread.domain.ticker.Exchange
import io.premiumspread.domain.ticker.Quote
import io.premiumspread.domain.ticker.Symbol
import io.premiumspread.domain.ticker.Ticker
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

class PremiumTest {
    @Test
    fun `한국 외국 환율 티커로 프리미엄을 계산한다`() {
        val koreaTicker = ticker(
            exchange = Exchange.UPBIT,
            quote = Quote.coin(Symbol("BTC"), Currency.KRW),
            price = "10000",
            observedAt = Instant.parse("2024-01-01T00:00:00Z"),
        )
        val foreignTicker = ticker(
            exchange = Exchange.BINANCE,
            quote = Quote.coin(Symbol("BTC"), Currency.USD),
            price = "7",
            observedAt = Instant.parse("2024-01-01T00:00:10Z"),
        )
        val fxTicker = ticker(
            exchange = Exchange.FX_PROVIDER,
            quote = Quote.fx(Currency.USD, Currency.KRW),
            price = "1000",
            observedAt = Instant.parse("2024-01-01T00:00:05Z"),
        )

        val premium = Premium.create(koreaTicker, foreignTicker, fxTicker)

        assertEquals(BigDecimal("42.86"), premium.premiumRate)
        assertEquals(foreignTicker.observedAt, premium.observedAt)
        assertEquals(koreaTicker.id, premium.koreaTickerId)
        assertEquals(foreignTicker.id, premium.foreignTickerId)
        assertEquals(fxTicker.id, premium.fxTickerId)
    }

    @Test
    fun `심볼이 다르면 예외를 던진다`() {
        val koreaTicker = ticker(
            exchange = Exchange.UPBIT,
            quote = Quote.coin(Symbol("BTC"), Currency.KRW),
            price = "10000",
            observedAt = Instant.parse("2024-01-01T00:00:00Z"),
        )
        val foreignTicker = ticker(
            exchange = Exchange.BINANCE,
            quote = Quote.coin(Symbol("ETH"), Currency.USD),
            price = "7",
            observedAt = Instant.parse("2024-01-01T00:00:00Z"),
        )
        val fxTicker = ticker(
            exchange = Exchange.FX_PROVIDER,
            quote = Quote.fx(Currency.USD, Currency.KRW),
            price = "1000",
            observedAt = Instant.parse("2024-01-01T00:00:00Z"),
        )

        assertThrows(InvalidPremiumInputException::class.java) {
            Premium.create(koreaTicker, foreignTicker, fxTicker)
        }
    }

    @Test
    fun `한국 티커의 거래소 지역이 한국이 아니면 예외를 던진다`() {
        val koreaTicker = ticker(
            exchange = Exchange.BINANCE,
            quote = Quote.coin(Symbol("BTC"), Currency.KRW),
            price = "10000",
            observedAt = Instant.parse("2024-01-01T00:00:00Z"),
        )
        val foreignTicker = ticker(
            exchange = Exchange.BINANCE,
            quote = Quote.coin(Symbol("BTC"), Currency.USD),
            price = "7",
            observedAt = Instant.parse("2024-01-01T00:00:00Z"),
        )
        val fxTicker = ticker(
            exchange = Exchange.FX_PROVIDER,
            quote = Quote.fx(Currency.USD, Currency.KRW),
            price = "1000",
            observedAt = Instant.parse("2024-01-01T00:00:00Z"),
        )

        assertThrows(InvalidPremiumInputException::class.java) {
            Premium.create(koreaTicker, foreignTicker, fxTicker)
        }
    }

    @Test
    fun `외국 티커의 통화가 USD가 아니면 예외를 던진다`() {
        val koreaTicker = ticker(
            exchange = Exchange.UPBIT,
            quote = Quote.coin(Symbol("BTC"), Currency.KRW),
            price = "10000",
            observedAt = Instant.parse("2024-01-01T00:00:00Z"),
        )
        val foreignTicker = ticker(
            exchange = Exchange.BINANCE,
            quote = Quote.coin(Symbol("BTC"), Currency.KRW),
            price = "7000",
            observedAt = Instant.parse("2024-01-01T00:00:00Z"),
        )
        val fxTicker = ticker(
            exchange = Exchange.FX_PROVIDER,
            quote = Quote.fx(Currency.USD, Currency.KRW),
            price = "1000",
            observedAt = Instant.parse("2024-01-01T00:00:00Z"),
        )

        assertThrows(InvalidPremiumInputException::class.java) {
            Premium.create(koreaTicker, foreignTicker, fxTicker)
        }
    }

    @Test
    fun `환율 티커 방향이 USD-KRW가 아니면 예외를 던진다`() {
        val koreaTicker = ticker(
            exchange = Exchange.UPBIT,
            quote = Quote.coin(Symbol("BTC"), Currency.KRW),
            price = "10000",
            observedAt = Instant.parse("2024-01-01T00:00:00Z"),
        )
        val foreignTicker = ticker(
            exchange = Exchange.BINANCE,
            quote = Quote.coin(Symbol("BTC"), Currency.USD),
            price = "7",
            observedAt = Instant.parse("2024-01-01T00:00:00Z"),
        )
        val fxTicker = ticker(
            exchange = Exchange.FX_PROVIDER,
            quote = Quote.fx(Currency.KRW, Currency.USD),
            price = "0.001",
            observedAt = Instant.parse("2024-01-01T00:00:00Z"),
        )

        assertThrows(InvalidPremiumInputException::class.java) {
            Premium.create(koreaTicker, foreignTicker, fxTicker)
        }
    }

    @Test
    fun `프리미엄 계산은 소수점 둘째 자리에서 반올림한다`() {
        val koreaTicker = ticker(
            exchange = Exchange.UPBIT,
            quote = Quote.coin(Symbol("BTC"), Currency.KRW),
            price = "1000.05",
            observedAt = Instant.parse("2024-01-01T00:00:00Z"),
        )
        val foreignTicker = ticker(
            exchange = Exchange.BINANCE,
            quote = Quote.coin(Symbol("BTC"), Currency.USD),
            price = "1",
            observedAt = Instant.parse("2024-01-01T00:00:00Z"),
        )
        val fxTicker = ticker(
            exchange = Exchange.FX_PROVIDER,
            quote = Quote.fx(Currency.USD, Currency.KRW),
            price = "1000",
            observedAt = Instant.parse("2024-01-01T00:00:00Z"),
        )

        val premium = Premium.create(koreaTicker, foreignTicker, fxTicker)

        assertEquals(BigDecimal("0.01"), premium.premiumRate)
    }

    @Test
    fun `Premium create는 단일 PremiumPolicy의 entity scale 결과를 사용한다`() {
        val koreaPrice = BigDecimal("50000000")
        val foreignPrice = BigDecimal("38000")
        val fxRate = BigDecimal("1320.50")
        val observedAt = Instant.parse("2024-01-01T00:00:00Z")

        val premium = Premium.create(
            ticker(Exchange.UPBIT, Quote.coin(Symbol("BTC"), Currency.KRW), koreaPrice.toPlainString(), observedAt),
            ticker(Exchange.BINANCE, Quote.coin(Symbol("BTC"), Currency.USD), foreignPrice.toPlainString(), observedAt),
            ticker(Exchange.FX_PROVIDER, Quote.fx(Currency.USD, Currency.KRW), fxRate.toPlainString(), observedAt),
        )

        val expected = PremiumPolicy.calculate(koreaPrice, foreignPrice, fxRate).entityPremiumRate

        assertThat(premium.premiumRate).isEqualByComparingTo(expected)
    }

    @Test
    fun `음수 프리미엄(역프리미엄)이 올바르게 계산된다`() {
        val koreaPrice = BigDecimal("40000000")
        val foreignPrice = BigDecimal("38000")
        val fxRate = BigDecimal("1320.50")
        val observedAt = Instant.parse("2024-01-01T00:00:00Z")

        val premium = Premium.create(
            ticker(Exchange.UPBIT, Quote.coin(Symbol("BTC"), Currency.KRW), koreaPrice.toPlainString(), observedAt),
            ticker(Exchange.BINANCE, Quote.coin(Symbol("BTC"), Currency.USD), foreignPrice.toPlainString(), observedAt),
            ticker(Exchange.FX_PROVIDER, Quote.fx(Currency.USD, Currency.KRW), fxRate.toPlainString(), observedAt),
        )

        assertThat(premium.premiumRate).isNegative
    }

    private fun ticker(
        exchange: Exchange,
        quote: Quote,
        price: String,
        observedAt: Instant,
    ): Ticker = Ticker.create(
            exchange = exchange,
            quote = quote,
            price = BigDecimal(price),
            observedAt = observedAt,
        )
}
