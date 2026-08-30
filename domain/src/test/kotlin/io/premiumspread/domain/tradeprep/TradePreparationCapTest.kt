package io.premiumspread.domain.tradeprep

import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * AC3 — 레버 캡·효율 캡·청산 거리 중 하나라도 위반하면 계획을 만들지 않고 위반한 캡을
 * 응답에 명시한다 (design.md §3, ECO-5 §7 "특정 캡에 도달하면 자동 매매를 중지한다").
 *
 * 레버 캡은 7배 · 효율 캡은 60% 를 이 테스트의 정책값으로 쓴다. 값은 [TradePrepPolicy]
 * 생성자 파라미터로만 주입한다 — 하드코딩된 상수를 참조하지 않는다.
 */
class TradePreparationCapTest {

    private val policy = TradePrepPolicy(
        leverageCap = BigDecimal("7"),
        efficiencyFloor = BigDecimal("0.60"),
        koreaLotSize = BigDecimal("0.0001"),
        foreignLotSize = BigDecimal("0.001"),
    )

    @Test
    fun `레버·효율 모두 캡 안쪽이면 위반이 없다`() {
        val verdict = policy.judge(leverage = bd("4.8494645383"), koreaShare = bd("0.8304498270"))

        assertThat(verdict.isViolated).isFalse
        assertThat(verdict.violations).isEmpty()
    }

    @Test
    fun `레버리지가 캡에 정확히 도달하면 위반이다 — 도달 자체가 정지 조건이다`() {
        val verdict = policy.judge(leverage = bd("7"), koreaShare = bd("0.875"))

        assertThat(verdict.violations).containsExactly(CapViolation.LEVERAGE_CAP)
    }

    @Test
    fun `레버리지가 캡 직전이면 위반이 아니다`() {
        val verdict = policy.judge(leverage = bd("6.9999992857"), koreaShare = bd("0.8749999888"))

        assertThat(verdict.isViolated).isFalse
    }

    @Test
    fun `레버리지가 캡을 넘으면 위반이다`() {
        val verdict = policy.judge(leverage = bd("7.0000007143"), koreaShare = bd("0.8750000112"))

        assertThat(verdict.violations).containsExactly(CapViolation.LEVERAGE_CAP)
    }

    @Test
    fun `빗썸 비중이 효율 하한과 정확히 같으면 위반이 아니다 — 미만일 때만 위반이다`() {
        val verdict = policy.judge(leverage = bd("1.5"), koreaShare = bd("0.60"))

        assertThat(verdict.isViolated).isFalse
    }

    @Test
    fun `빗썸 비중이 효율 하한 미만이면 위반이다`() {
        val verdict = policy.judge(leverage = bd("1.4999992857"), koreaShare = bd("0.5999998857"))

        assertThat(verdict.violations).containsExactly(CapViolation.EFFICIENCY_CAP)
    }

    @Test
    fun `빗썸 비중이 효율 하한 바로 위면 위반이 아니다`() {
        val verdict = policy.judge(leverage = bd("1.5000007143"), koreaShare = bd("0.6000001143"))

        assertThat(verdict.isViolated).isFalse
    }

    @Test
    fun `레버 캡과 효율 캡을 동시에 위반할 수 있다 — 둘은 독립 판정이다`() {
        // koreaShare 는 잔고 비율(R)에서만 나오고, leverage 는 R 을 프리미엄(P)으로 나눈 값이라
        // 극단적인 역프리미엄에서는 share 가 낮아도(R 이 낮아도) leverage 는 캡을 넘을 수 있다.
        val verdict = policy.judge(leverage = bd("9.3333333333"), koreaShare = bd("0.5833333333"))

        assertThat(verdict.violations).containsExactlyInAnyOrder(CapViolation.LEVERAGE_CAP, CapViolation.EFFICIENCY_CAP)
    }

    @Test
    fun `청산 거리는 레버리지의 역수로 관찰값에 담긴다`() {
        val verdict = policy.judge(leverage = bd("4"), koreaShare = bd("0.80"))

        assertThat(verdict.liquidationDistance).isEqualByComparingTo("0.25")
    }

    @Test
    fun `레버리지가 0이면 청산 거리는 0으로 담긴다 — division by zero를 던지지 않는다`() {
        val verdict = policy.judge(leverage = BigDecimal.ZERO, koreaShare = bd("0.60"))

        assertThat(verdict.liquidationDistance).isEqualByComparingTo(BigDecimal.ZERO)
        assertThat(verdict.violations).isEmpty()
    }

    @Test
    fun `캡 값은 하드코딩이 아니라 정책 생성자 값을 그대로 따른다`() {
        val looserPolicy = TradePrepPolicy(
            leverageCap = BigDecimal("10"),
            efficiencyFloor = BigDecimal("0.50"),
            koreaLotSize = BigDecimal("0.0001"),
            foreignLotSize = BigDecimal("0.001"),
        )

        // 기본 정책(cap=7)이면 위반이었을 leverage=8 이 cap=10 정책에서는 위반이 아니다.
        val verdict = looserPolicy.judge(leverage = bd("8"), koreaShare = bd("0.55"))

        assertThat(verdict.isViolated).isFalse
    }

    private fun bd(value: String) = BigDecimal(value)
}
