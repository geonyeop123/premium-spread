package io.premiumspread.domain.ownermarket

import java.math.BigDecimal
import java.time.Instant

object PremiumThresholdPolicy {
    /** design 7.2(B) classify 1~5. prev null = 히스테리시스 없이 분류. */
    fun classify(rate: BigDecimal, prev: PremiumZone?, baseline: ThresholdBaseline): PremiumZone {
        val upper = baseline.upper
        val lower = baseline.lower
        return when {
            upper != null && rate >= upper -> PremiumZone.ABOVE

            lower != null && rate <= lower -> PremiumZone.BELOW

            prev == PremiumZone.ABOVE && upper != null && rate > upper - baseline.hysteresis -> PremiumZone.ABOVE

            prev == PremiumZone.BELOW && lower != null && rate < lower + baseline.hysteresis -> PremiumZone.BELOW

            else -> PremiumZone.BETWEEN
        }
    }

    /**
     * design 7.2(B) H1~H6. 기준선 꺼짐(settings.thresholdBaseline == null)이면 호출하지 않는다 - 호출하면 예외.
     * 기준선이 바뀐 첫 회차(H2)는 이미 넘어 있어도 알리지 않고 구역만 다시 정한다.
     */
    fun decide(
        observation: MarketObservation,
        state: MarketAlertState,
        settings: MarketAlertSettings,
        now: Instant,
    ): MarketDecision {
        MarketKeys.requireKey(state, MarketKeys.threshold(MarketKeys.pairKey(observation.pair)))
        val baseline = requireNotNull(settings.thresholdBaseline) { "threshold is disabled." }

        val current = observation.current ?: return MarketDecision.NONE
        val rate = current.premiumRate
        val stored = state.baseline
        val previous = state.zone
        if (state.version == 0L || stored == null || previous == null || !stored.sameAs(baseline)) {
            val reset = state.copy(zone = classify(rate, null, baseline), baseline = baseline)
            return MarketDecision(listOf(reset), emptyList())
        }

        val zone = classify(rate, previous, baseline)
        return when (zone) {
            previous -> MarketDecision.NONE

            PremiumZone.ABOVE ->
                crossed(observation, state, zone, now, ThresholdLine.UPPER, checkNotNull(baseline.upper), baseline)

            PremiumZone.BELOW ->
                crossed(observation, state, zone, now, ThresholdLine.LOWER, checkNotNull(baseline.lower), baseline)

            PremiumZone.BETWEEN -> MarketDecision(listOf(state.copy(zone = zone)), emptyList())
        }
    }

    private fun crossed(
        observation: MarketObservation,
        state: MarketAlertState,
        zone: PremiumZone,
        now: Instant,
        line: ThresholdLine,
        threshold: BigDecimal,
        baseline: ThresholdBaseline,
    ): MarketDecision {
        val current = checkNotNull(observation.current)
        val notice = MarketNotice.ThresholdCrossed(
            pair = observation.pair,
            at = now,
            line = line,
            threshold = threshold,
            hysteresis = baseline.hysteresis,
            current = current.premiumRate,
            currentObservedAt = current.observedAt,
        )
        return MarketDecision(listOf(state.copy(zone = zone, lastNotifiedAt = now)), listOf(notice))
    }
}
