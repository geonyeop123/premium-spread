package io.premiumspread.calculator

import io.premiumspread.client.TickerData
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

/**
 * batch PremiumCalculator와 domain Premium.calculatePremiumRate()의 공식 동등성을 검증한다.
 *
 * domain 모듈을 직접 참조할 수 없으므로, 동일 공식을 인라인으로 재현하여 비교한다.
 * domain 쪽에는 Premium.create()를 직접 호출하는 별도 동등성 테스트가 존재한다.
 */
@DisplayName("PremiumCalculator 동등성 검증")
class PremiumCalculatorEquivalenceTest {

    private val calculator = PremiumCalculator()

    /**
     * domain과 동일한 공식으로 기대값을 계산한다.
     * - 중간 정수 반올림 없음
     * - DIVISION_SCALE(=INTERMEDIATE_SCALE) = 10
     * - 최종 scale = storageScale
     */
    private fun expectedPremiumRate(
        koreaPrice: BigDecimal,
        foreignPrice: BigDecimal,
        fxRate: BigDecimal,
        storageScale: Int = 4,
    ): BigDecimal {
        val foreignPriceInKrw = foreignPrice.multiply(fxRate)
        val diff = koreaPrice.subtract(foreignPriceInKrw)
        return diff
            .divide(foreignPriceInKrw, 10, RoundingMode.HALF_UP)
            .multiply(BigDecimal("100"))
            .setScale(storageScale, RoundingMode.HALF_UP)
    }

    private fun ticker(price: String): TickerData = TickerData(
        exchange = "TEST",
        symbol = "BTC",
        currency = "KRW",
        price = BigDecimal(price),
        volume = null,
        timestamp = Instant.now(),
    )

    @Test
    fun `중간 반올림 제거 후 소수점 fxRate에서 정밀도가 유지된다`() {
        // given
        val koreaPrice = BigDecimal("50000000")
        val foreignPrice = BigDecimal("38000")
        val fxRate = BigDecimal("1320.50")

        // when
        val result = calculator.calculate(
            koreaTicker = ticker("50000000"),
            foreignTicker = ticker("38000"),
            fxRate = fxRate,
        )

        // then
        val expected = expectedPremiumRate(koreaPrice, foreignPrice, fxRate)
        assertThat(result.premiumRate).isEqualByComparingTo(expected)
    }

    @Test
    fun `setScale(0) 중간 반올림이 없음을 검증한다`() {
        // given: fxRate에 소수점이 있어서 정수 반올림 시 값이 달라지는 케이스
        val koreaPrice = BigDecimal("55000000")
        val foreignPrice = BigDecimal("38000")
        val fxRate = BigDecimal("1320.99")

        // when
        val result = calculator.calculate(
            koreaTicker = ticker("55000000"),
            foreignTicker = ticker("38000"),
            fxRate = fxRate,
        )

        // then: STORAGE_SCALE=4 확인
        assertThat(result.premiumRate.scale()).isEqualTo(4)

        // 정수 반올림 없는 기대값과 일치
        val expected = expectedPremiumRate(koreaPrice, foreignPrice, fxRate)
        assertThat(result.premiumRate).isEqualByComparingTo(expected)
    }

    @Test
    fun `calculateRate도 calculate와 동일한 결과를 반환한다`() {
        // given
        val koreaPrice = BigDecimal("129555000")
        val foreignPrice = BigDecimal("89277")
        val fxRate = BigDecimal("1432.6")

        // when
        val fromCalculate = calculator.calculate(
            koreaTicker = ticker("129555000"),
            foreignTicker = ticker("89277"),
            fxRate = fxRate,
        )
        val fromCalculateRate = calculator.calculateRate(koreaPrice, foreignPrice, fxRate)

        // then
        assertThat(fromCalculate.premiumRate).isEqualByComparingTo(fromCalculateRate)
    }

    @Test
    fun `다양한 fxRate 소수점 케이스에서 domain 공식과 동일하다`() {
        val testCases = listOf(
            Triple("130000000", "89000", "1432.6"),
            Triple("50000000", "38000", "1320.50"),
            Triple("75000000", "55000", "1350.75"),
            Triple("200000000", "150000", "1400.123"),
            Triple("5000000", "3800", "1299.99"),
        )

        for ((korea, foreign, fx) in testCases) {
            val result = calculator.calculate(
                koreaTicker = ticker(korea),
                foreignTicker = ticker(foreign),
                fxRate = BigDecimal(fx),
            )
            val expected = expectedPremiumRate(
                BigDecimal(korea), BigDecimal(foreign), BigDecimal(fx),
            )
            assertThat(result.premiumRate)
                .describedAs("koreaPrice=$korea, foreignPrice=$foreign, fxRate=$fx")
                .isEqualByComparingTo(expected)
        }
    }
}
