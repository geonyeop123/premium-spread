package io.premiumspread.domain.tradeprep

import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * AC2 — `R`·`L`·`Q` 산출이 ECO-5 §2 관계식과 일치하고, lot/step-size 반올림 뒤 `Q`·`L`·캡을
 * **다시 판정**하며 양 leg 수량이 같다 (design.md §3, D12). 경계값(캡 직전·직후, 잔고 0,
 * 반올림이 캡을 넘기는 경우)을 포함한다.
 *
 * 기대값은 `divide(scale=10, HALF_UP)` 로 파생 계산을 미리 검산해 문자열로 고정했다.
 */
class TradePreparationSizingTest {

    private val policy = TradePrepPolicy(
        leverageCap = BigDecimal("7"),
        efficiencyFloor = BigDecimal("0.60"),
        koreaLotSize = BigDecimal("0.0001"),
        foreignLotSize = BigDecimal("0.001"),
    )

    // ---- ECO-5 §2 관계식 (순수 함수) ----

    @Test
    fun `R = B_k over (X times B_b)`() {
        val r = TradePrepSizing.balanceRatio(
            koreaBalance = bd("24000000"),
            fxRate = bd("1400"),
            foreignBalance = bd("3500"),
        )

        assertThat(r).isEqualByComparingTo("4.8979591837")
    }

    @Test
    fun `L = R over (1+P) — 레버리지는 R의 함수다`() {
        val l = TradePrepSizing.leverage(balanceRatio = bd("4.8979591837"), premiumRatePercent = bd("1.00"))

        assertThat(l).isEqualByComparingTo("4.8494645383")
    }

    @Test
    fun `Q = B_k over K, K = F times X times (1+P)`() {
        val q = TradePrepSizing.quantity(
            koreaBalance = bd("24000000"),
            foreignPrice = bd("70000"),
            fxRate = bd("1400"),
            premiumRatePercent = bd("1.00"),
        )

        assertThat(q).isEqualByComparingTo("0.2424732269")
    }

    @Test
    fun `빗썸 비중 = B_k over (B_k + X times B_b), 물량과 무관하다`() {
        val share = TradePrepSizing.koreaShare(
            koreaBalance = bd("24000000"),
            fxRate = bd("1400"),
            foreignBalance = bd("3500"),
        )

        assertThat(share).isEqualByComparingTo("0.8304498270")
    }

    // ---- 반올림과 재판정 파이프라인 (D12) ----

    @Test
    fun `캡 안쪽이면 lot size로 내림 반올림하고 작은 쪽 물량을 채택해 leverage를 재계산한다`() {
        val result = TradePrepSizing.size(
            koreaBalance = bd("24000000"),
            foreignBalance = bd("3500"),
            fxRate = bd("1400"),
            foreignPrice = bd("70000"),
            premiumRatePercent = bd("1.00"),
            policy = policy,
        )

        assertThat(result.rawQuantity).isEqualByComparingTo("0.2424732269")
        assertThat(result.koreaRoundedQuantity).isEqualByComparingTo("0.2424")
        assertThat(result.foreignRoundedQuantity).isEqualByComparingTo("0.242")
        assertThat(result.finalQuantity).isEqualByComparingTo("0.242")
        assertThat(result.finalLeverage).isEqualByComparingTo("4.8400000000")
        assertThat(result.capVerdict.isViolated).isFalse
        assertThat(result.isPlannable).isTrue
    }

    @Test
    fun `양 leg 반올림 물량이 다르면 작은 쪽에 맞춘다`() {
        val coarseForeignLotPolicy = TradePrepPolicy(
            leverageCap = BigDecimal("7"),
            efficiencyFloor = BigDecimal("0.60"),
            koreaLotSize = BigDecimal("0.0001"),
            foreignLotSize = BigDecimal("0.01"),
        )

        val result = TradePrepSizing.size(
            koreaBalance = bd("24000000"),
            foreignBalance = bd("3500"),
            fxRate = bd("1400"),
            foreignPrice = bd("70000"),
            premiumRatePercent = bd("1.00"),
            policy = coarseForeignLotPolicy,
        )

        assertThat(result.koreaRoundedQuantity).isEqualByComparingTo("0.2424")
        assertThat(result.foreignRoundedQuantity).isEqualByComparingTo("0.24")
        // foreign 쪽이 더 거칠어 작으므로 최종 물량은 foreign 쪽을 따른다.
        assertThat(result.finalQuantity).isEqualByComparingTo("0.24")
        assertThat(result.finalLeverage).isEqualByComparingTo("4.8000000000")
        assertThat(result.isPlannable).isTrue
    }

    @Test
    fun `반올림이 물량을 0으로 만들면 캡이 안쪽이어도 계획을 만들지 않는다`() {
        // R·L·share 모두 캡 안쪽이지만(레버 1.6배, 빗썸비중 61.6%), 절대 자본이 작아
        // 바이낸스 lot size(0.001)보다 raw quantity(0.000918)가 작다.
        val result = TradePrepSizing.size(
            koreaBalance = bd("90000"),
            foreignBalance = bd("40"),
            fxRate = bd("1400"),
            foreignPrice = bd("70000"),
            premiumRatePercent = bd("0.00"),
            policy = policy,
        )

        assertThat(result.rawQuantity).isEqualByComparingTo("0.0009183673")
        assertThat(result.capVerdict.isViolated).isFalse
        assertThat(result.koreaRoundedQuantity).isEqualByComparingTo("0.0009")
        assertThat(result.foreignRoundedQuantity).isEqualByComparingTo("0.000")
        assertThat(result.finalQuantity).isEqualByComparingTo(BigDecimal.ZERO)
        assertThat(result.isPlannable).isFalse
    }

    @Test
    fun `반올림 후 재계산한 leverage가 캡 원시값보다 크지 않다 — 보수적 방향`() {
        val result = TradePrepSizing.size(
            koreaBalance = bd("24000000"),
            foreignBalance = bd("3500"),
            fxRate = bd("1400"),
            foreignPrice = bd("70000"),
            premiumRatePercent = bd("1.00"),
            policy = policy,
        )

        assertThat(result.finalLeverage).isLessThanOrEqualTo(result.rawLeverage)
        assertThat(result.finalQuantity).isLessThanOrEqualTo(result.rawQuantity)
    }

    // ---- 경계값: 캡 직전·직후 ----

    @Test
    fun `레버리지가 캡 직전이면 계획 가능하다`() {
        val result = TradePrepSizing.size(
            koreaBalance = bd("9799999"),
            foreignBalance = bd("1000"),
            fxRate = bd("1400"),
            foreignPrice = bd("70000"),
            premiumRatePercent = bd("0.00"),
            policy = policy,
        )

        assertThat(result.rawLeverage).isEqualByComparingTo("6.9999992857")
        assertThat(result.capVerdict.violations).isEmpty()
    }

    @Test
    fun `레버리지가 캡에 도달하면 계획을 만들지 않고 위반한 캡을 명시한다`() {
        val result = TradePrepSizing.size(
            koreaBalance = bd("9800000"),
            foreignBalance = bd("1000"),
            fxRate = bd("1400"),
            foreignPrice = bd("70000"),
            premiumRatePercent = bd("0.00"),
            policy = policy,
        )

        assertThat(result.rawLeverage).isEqualByComparingTo("7.0000000000")
        assertThat(result.capVerdict.violations).contains(CapViolation.LEVERAGE_CAP)
        assertThat(result.isPlannable).isFalse
    }

    // ---- 경계값: 잔고 0 ----

    @Test
    fun `빗썸 잔고가 0이면 물량도 0이고 계획을 만들지 않는다 — 예외를 던지지 않는다`() {
        val result = TradePrepSizing.size(
            koreaBalance = BigDecimal.ZERO,
            foreignBalance = bd("1000"),
            fxRate = bd("1400"),
            foreignPrice = bd("70000"),
            premiumRatePercent = bd("0.00"),
            policy = policy,
        )

        assertThat(result.rawQuantity).isEqualByComparingTo(BigDecimal.ZERO)
        assertThat(result.finalQuantity).isEqualByComparingTo(BigDecimal.ZERO)
        assertThat(result.isPlannable).isFalse
    }

    @Test
    fun `바이낸스 잔고가 0이면 나눗셈이 불가능하므로 거부한다`() {
        assertThatThrownBy {
            TradePrepSizing.size(
                koreaBalance = bd("24000000"),
                foreignBalance = BigDecimal.ZERO,
                fxRate = bd("1400"),
                foreignPrice = bd("70000"),
                premiumRatePercent = bd("1.00"),
                policy = policy,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    private fun bd(value: String) = BigDecimal(value)
}
