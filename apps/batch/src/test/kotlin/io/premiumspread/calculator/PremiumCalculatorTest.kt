package io.premiumspread.calculator

import io.premiumspread.client.TickerData
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

class PremiumCalculatorTest {

    private lateinit var calculator: PremiumCalculator

    @BeforeEach
    fun setUp() {
        calculator = PremiumCalculator()
    }

    private fun createTicker(
        exchange: String,
        symbol: String,
        currency: String,
        price: String,
    ): TickerData = TickerData(
        exchange = exchange,
        symbol = symbol,
        currency = currency,
        price = BigDecimal(price),
        volume = null,
        timestamp = Instant.now(),
    )

    @Nested
    @DisplayName("calculate")
    inner class Calculate {

        @Test
        fun `should calculate positive premium when korea price is higher`() {
            // given
            val koreaTicker = createTicker("BITHUMB", "BTC", "KRW", "129555000")
            val foreignTicker = createTicker("BINANCE", "BTC", "USDT", "89277")
            val fxRate = BigDecimal("1432.6")

            // when
            val result = calculator.calculate(koreaTicker, foreignTicker, fxRate)

            // then
            assertThat(result.symbol).isEqualTo("BTC")
            assertThat(result.premiumRate).isPositive
            assertThat(result.koreaPrice).isEqualByComparingTo(BigDecimal("129555000"))
            assertThat(result.foreignPrice).isEqualByComparingTo(BigDecimal("89277"))
            assertThat(result.fxRate).isEqualByComparingTo(BigDecimal("1432.6"))
        }

        @Test
        fun `should calculate negative premium when korea price is lower`() {
            // given
            val koreaTicker = createTicker("BITHUMB", "BTC", "KRW", "125000000")
            val foreignTicker = createTicker("BINANCE", "BTC", "USDT", "90000")
            val fxRate = BigDecimal("1432.6")
            // 해외가격 원화환산: 90000 * 1432.6 = 128,934,000 KRW

            // when
            val result = calculator.calculate(koreaTicker, foreignTicker, fxRate)

            // then
            assertThat(result.premiumRate).isNegative
            // (125000000 - 128934000) / 128934000 * 100 ≈ -3.05%
        }

        @Test
        fun `should calculate zero premium when prices are equal`() {
            // given
            val fxRate = BigDecimal("1432.6")
            val foreignPrice = BigDecimal("90000")
            val expectedKoreaPrice = foreignPrice.multiply(fxRate) // 128,934,000

            val koreaTicker = createTicker("BITHUMB", "BTC", "KRW", expectedKoreaPrice.toPlainString())
            val foreignTicker = createTicker("BINANCE", "BTC", "USDT", foreignPrice.toPlainString())

            // when
            val result = calculator.calculate(koreaTicker, foreignTicker, fxRate)

            // then
            assertThat(result.premiumRate).isEqualByComparingTo(BigDecimal.ZERO)
        }

        @Test
        fun `should calculate typical kimchi premium scenario`() {
            // given: 실제 김프 시나리오 (약 1.28%)
            // 한국가격: 129,555,000 KRW
            // 해외가격: 89,277 USDT
            // 환율: 1,432.6 KRW/USD
            // 해외가격 원화: 89,277 * 1,432.6 = 127,918,150 KRW
            // 김프: (129555000 - 127918150) / 127918150 * 100 ≈ 1.28%

            val koreaTicker = createTicker("BITHUMB", "BTC", "KRW", "129555000")
            val foreignTicker = createTicker("BINANCE", "BTC", "USDT", "89277")
            val fxRate = BigDecimal("1432.6")

            // when
            val result = calculator.calculate(koreaTicker, foreignTicker, fxRate)

            // then
            assertThat(result.premiumRate)
                .isBetween(BigDecimal("1.0"), BigDecimal("2.0")) // 약 1.28%
        }

        @Test
        fun `should set foreignPriceInKrw correctly`() {
            // given
            val koreaTicker = createTicker("BITHUMB", "BTC", "KRW", "129555000")
            val foreignTicker = createTicker("BINANCE", "BTC", "USDT", "89277")
            val fxRate = BigDecimal("1432.6")

            // when
            val result = calculator.calculate(koreaTicker, foreignTicker, fxRate)

            // then
            val expectedForeignPriceInKrw = BigDecimal("89277").multiply(BigDecimal("1432.6"))
            assertThat(result.foreignPriceInKrw)
                .isCloseTo(expectedForeignPriceInKrw, org.assertj.core.data.Offset.offset(BigDecimal("1")))
        }
    }

    @Nested
    @DisplayName("calculateRate")
    inner class CalculateRate {

        @Test
        fun `should calculate rate only`() {
            // given
            val koreaPrice = BigDecimal("129555000")
            val foreignPrice = BigDecimal("89277")
            val fxRate = BigDecimal("1432.6")

            // when
            val rate = calculator.calculateRate(koreaPrice, foreignPrice, fxRate)

            // then
            assertThat(rate).isPositive
            assertThat(rate).isBetween(BigDecimal("1.0"), BigDecimal("2.0"))
        }
    }
}
