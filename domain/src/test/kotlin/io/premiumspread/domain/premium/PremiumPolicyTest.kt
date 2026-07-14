package io.premiumspread.domain.premium

import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class PremiumPolicyTest {
    @Test
    fun `외부 저장값을 엔티티와 표시 scale로 동일한 HALF UP 정책으로 정규화한다`() {
        assertThat(PremiumPolicy.normalizeEntity(bd("1.2349"))).isEqualByComparingTo("1.23")
        assertThat(PremiumPolicy.normalizeEntity(bd("1.2350"))).isEqualByComparingTo("1.24")
        assertThat(PremiumPolicy.normalizeDisplay(bd("-1.2350"))).isEqualByComparingTo("-1.24")
    }

    @Test
    fun `계산 저장 엔티티 API scale을 명시적으로 분리한다`() {
        val result = PremiumPolicy.calculate(bd("1000.055"), bd("1"), bd("1000"))

        assertThat(result.storagePremiumRate).isEqualByComparingTo("0.0055")
        assertThat(result.entityPremiumRate).isEqualByComparingTo("0.01")
        assertThat(result.apiDisplayPremiumRate).isEqualByComparingTo("0.01")
    }

    @Test
    fun `역프리미엄을 보존한다`() {
        val result = PremiumPolicy.calculate(bd("900"), bd("1"), bd("1000"))

        assertThat(result.storagePremiumRate).isEqualByComparingTo("-10.0000")
    }

    @Test
    fun `0과 음수 입력을 거부한다`() {
        listOf(
            Triple("0", "1", "1000"),
            Triple("1000", "-1", "1000"),
            Triple("1000", "1", "0"),
        ).forEach { (korea, foreign, fx) ->
            assertThatThrownBy { PremiumPolicy.calculate(bd(korea), bd(foreign), bd(fx)) }
                .isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun `극단 환율도 overflow 없이 계산한다`() {
        val result = PremiumPolicy.calculate(bd("1E+30"), bd("1E-10"), bd("1E+20"))

        assertThat(result.storagePremiumRate).isEqualByComparingTo("9999999999999999999900.0000")
    }

    private fun bd(value: String) = BigDecimal(value)
}
