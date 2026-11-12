package io.premiumspread.domain.accountpnl

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class ForeignPerpRiskMetricsTest {
    private val one = BigDecimal.ONE

    private fun risk(
        liquidationPrice: BigDecimal? = BigDecimal("50"),
        markPrice: BigDecimal = BigDecimal("100"),
        isolatedMargin: BigDecimal = one,
        isolatedWallet: BigDecimal = one,
        maintMargin: BigDecimal = one,
        positionInitialMargin: BigDecimal = one,
        notional: BigDecimal = BigDecimal("100"),
    ) = ForeignPerpPositionRisk(
        liquidationPrice, markPrice, isolatedMargin, isolatedWallet, maintMargin, positionInitialMargin, notional,
    )

    @Test
    fun `마크가가 0 이하이면 거부한다`() {
        assertThatThrownBy { risk(markPrice = BigDecimal.ZERO) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { risk(markPrice = BigDecimal("-1")) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `청산가는 null 이거나 양수다`() {
        assertThatCode { risk(liquidationPrice = null) }.doesNotThrowAnyException()
        assertThatCode { risk(liquidationPrice = BigDecimal("0.1")) }.doesNotThrowAnyException()
        assertThatThrownBy { risk(liquidationPrice = BigDecimal.ZERO) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { risk(liquidationPrice = BigDecimal("-1")) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `증거금 4종이 음수이면 거부한다`() {
        val neg = BigDecimal("-0.01")
        assertThatThrownBy { risk(isolatedMargin = neg) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { risk(isolatedWallet = neg) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { risk(maintMargin = neg) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { risk(positionInitialMargin = neg) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatCode { risk(isolatedMargin = BigDecimal.ZERO, isolatedWallet = BigDecimal.ZERO) }
            .doesNotThrowAnyException()
    }

    @Test
    fun `notional 은 음수를 허용한다`() {
        assertThat(risk(notional = BigDecimal("-100")).notional).isEqualByComparingTo("-100")
    }

    @Test
    fun `레버리지가 1 미만이면 거부한다`() {
        assertThatThrownBy { ForeignPerpSymbolConfig(0, ForeignPerpMarginType.CROSSED) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { ForeignPerpSymbolConfig(-3, ForeignPerpMarginType.ISOLATED) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThat(ForeignPerpSymbolConfig(1, ForeignPerpMarginType.CROSSED).leverage).isEqualTo(1)
    }

    @Test
    fun `toString 에 금액이 없다`() {
        val text = risk(
            liquidationPrice = BigDecimal("12345.678"),
            markPrice = BigDecimal("98765.4321"),
            notional = BigDecimal("-777.77"),
        ).toString()
        assertThat(text).doesNotContain("12345").doesNotContain("98765").doesNotContain("777")
        val cfg = ForeignPerpSymbolConfig(37, ForeignPerpMarginType.ISOLATED).toString()
        assertThat(cfg).doesNotContain("37")
    }
}
