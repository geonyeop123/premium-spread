package io.premiumspread.domain.ownermarket

import io.premiumspread.domain.market.MarketPair
import io.premiumspread.domain.owneralert.OwnerAlertKind
import io.premiumspread.domain.premium.PremiumSnapshot
import io.premiumspread.domain.ticker.Symbol
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

class PremiumThresholdPolicyTest {
    private val now = Instant.parse("2026-10-29T00:00:30Z")
    private val earlier = Instant.parse("2026-10-28T12:00:00Z")
    private val pair = MarketPair.default(Symbol("BTC"))
    private val key = MarketKeys.threshold(MarketKeys.pairKey(pair))
    private val settings = MarketAlertSettings(upperThreshold = BigDecimal("3.0"), lowerThreshold = BigDecimal("1.0"))
    private val baseline = settings.thresholdBaseline!!

    private fun row(zone: PremiumZone, base: ThresholdBaseline = baseline, last: Instant? = earlier) =
        MarketAlertState(key, OwnerAlertKind.PREMIUM_THRESHOLD_CROSSED, last, zone, base, null, 4)

    private fun observation(rate: String?) = MarketObservation(
        pair,
        rate?.let {
            PremiumSnapshot(
                pair,
                BigDecimal(it),
                BigDecimal("100"),
                BigDecimal("1"),
                BigDecimal("100"),
                BigDecimal("1400"),
                now,
            )
        },
        null,
        null,
    )

    private fun decide(
        rate: String?,
        state: MarketAlertState,
        s: MarketAlertSettings = settings,
    ) = PremiumThresholdPolicy.decide(observation(rate), state, s, now)

    private fun crossed(line: ThresholdLine, threshold: String, rate: String) = MarketNotice.ThresholdCrossed(
        pair,
        now,
        line,
        BigDecimal(threshold),
        BigDecimal("0.1"),
        BigDecimal(rate),
        now,
    )

    @Test
    fun `a - 행 없음은 기준을 재설정하고 알리지 않는다`() {
        val result = decide("3.5", MarketAlertState.initial(key, OwnerAlertKind.PREMIUM_THRESHOLD_CROSSED))

        assertThat(result.notices).isEmpty()
        assertThat(result.next).containsExactly(
            MarketAlertState(key, OwnerAlertKind.PREMIUM_THRESHOLD_CROSSED, null, PremiumZone.ABOVE, baseline, null, 0),
        )
    }

    @Test
    fun `b - 상단 3_0 은 발동하고 2_9999 는 발동하지 않는다`() {
        val fired = decide("3.0", row(PremiumZone.BETWEEN))

        assertThat(fired.notices).containsExactly(crossed(ThresholdLine.UPPER, "3.0", "3.0"))
        assertThat(fired.next).containsExactly(row(PremiumZone.ABOVE, last = now))
        assertThat(decide("2.9999", row(PremiumZone.BETWEEN))).isEqualTo(MarketDecision.NONE)
    }

    @Test
    fun `c - ABOVE 는 2_91 유지 2_90 이탈 이후 재돌파 시 알린다`() {
        assertThat(decide("2.91", row(PremiumZone.ABOVE))).isEqualTo(MarketDecision.NONE)

        val left = decide("2.90", row(PremiumZone.ABOVE))
        assertThat(left.notices).isEmpty()
        assertThat(left.next).containsExactly(row(PremiumZone.BETWEEN))

        val again = decide("3.0", row(PremiumZone.BETWEEN))
        assertThat(again.notices).hasSize(1)
    }

    @Test
    fun `d - 하단 대칭`() {
        val fired = decide("1.0", row(PremiumZone.BETWEEN))
        assertThat(fired.notices).containsExactly(crossed(ThresholdLine.LOWER, "1.0", "1.0"))
        assertThat(fired.next).containsExactly(row(PremiumZone.BELOW, last = now))

        assertThat(decide("1.09", row(PremiumZone.BELOW))).isEqualTo(MarketDecision.NONE)
        val left = decide("1.10", row(PremiumZone.BELOW))
        assertThat(left.notices).isEmpty()
        assertThat(left.next).containsExactly(row(PremiumZone.BETWEEN))
    }

    @Test
    fun `e - ABOVE 에서 BELOW 로 직행하면 LOWER 알림`() {
        val result = decide("0.9", row(PremiumZone.ABOVE))

        assertThat(result.notices).containsExactly(crossed(ThresholdLine.LOWER, "1.0", "0.9"))
        assertThat(result.next).containsExactly(row(PremiumZone.BELOW, last = now))
    }

    @Test
    fun `f - 저장 기준과 설정이 다르면 알림 없이 재분류하고 마지막 알림 시각을 유지한다`() {
        val stored = ThresholdBaseline(BigDecimal("3.0"), BigDecimal("1.0"), BigDecimal("0.1"))
        val changed = MarketAlertSettings(upperThreshold = BigDecimal("3.2"), lowerThreshold = BigDecimal("1.0"))

        val result = decide("3.1", row(PremiumZone.ABOVE, stored), changed)

        assertThat(result.notices).isEmpty()
        assertThat(result.next).containsExactly(row(PremiumZone.BETWEEN, changed.thresholdBaseline!!))
    }

    @Test
    fun `g - 상단만 설정하면 0_5 는 BETWEEN`() {
        val onlyUpper = MarketAlertSettings(upperThreshold = BigDecimal("3.0"))
        val upperBase = onlyUpper.thresholdBaseline!!

        val result = decide("0.5", row(PremiumZone.ABOVE, upperBase), onlyUpper)

        assertThat(result.notices).isEmpty()
        assertThat(result.next).containsExactly(row(PremiumZone.BETWEEN, upperBase))
        assertThat(PremiumThresholdPolicy.classify(BigDecimal("-50"), PremiumZone.BETWEEN, upperBase))
            .isEqualTo(PremiumZone.BETWEEN)
    }

    @Test
    fun `h - 현재값이 없으면 아무것도 하지 않는다`() {
        assertThat(decide(null, row(PremiumZone.ABOVE))).isEqualTo(MarketDecision.NONE)
    }

    @Test
    fun `i - 저장 3_0000 과 설정 3_0 은 같은 기준선이다`() {
        val stored = ThresholdBaseline(BigDecimal("3.0000"), BigDecimal("1.0000"), BigDecimal("0.1000"))

        assertThat(decide("2.95", row(PremiumZone.ABOVE, stored))).isEqualTo(MarketDecision.NONE)
    }

    @Test
    fun `j - 꺼짐 설정과 key 불일치는 예외`() {
        assertThatThrownBy { decide("2.0", row(PremiumZone.BETWEEN), MarketAlertSettings()) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("threshold is disabled.")

        val otherKey = MarketKeys.swing(MarketKeys.pairKey(pair), MarketDirection.UP)
        val other = MarketAlertState.initial(otherKey, OwnerAlertKind.PREMIUM_SWING)
        assertThatThrownBy { decide("2.0", other) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("market state key mismatch.")
    }

    @Test
    fun `classify 경계`() {
        assertThat(PremiumThresholdPolicy.classify(BigDecimal("3.0"), null, baseline)).isEqualTo(PremiumZone.ABOVE)
        assertThat(PremiumThresholdPolicy.classify(BigDecimal("2.9"), PremiumZone.ABOVE, baseline))
            .isEqualTo(PremiumZone.BETWEEN)
        assertThat(PremiumThresholdPolicy.classify(BigDecimal("2.91"), PremiumZone.ABOVE, baseline))
            .isEqualTo(PremiumZone.ABOVE)
        assertThat(PremiumThresholdPolicy.classify(BigDecimal("2.91"), null, baseline)).isEqualTo(PremiumZone.BETWEEN)
        assertThat(PremiumThresholdPolicy.classify(BigDecimal("1.0"), null, baseline)).isEqualTo(PremiumZone.BELOW)
        assertThat(PremiumThresholdPolicy.classify(BigDecimal("1.1"), PremiumZone.BELOW, baseline))
            .isEqualTo(PremiumZone.BETWEEN)
    }
}
