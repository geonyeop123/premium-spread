package io.premiumspread.domain.ownermarket

import io.premiumspread.domain.premium.PremiumAggregationSnapshot
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

data class ExtremeStates(
    val hourHigh: MarketAlertState,
    val hourLow: MarketAlertState,
    val dayHigh: MarketAlertState,
    val dayLow: MarketAlertState,
)

object PremiumExtremePolicy {
    /**
     * design 7.2(C) E1~E5. 방향(UP=HIGH, DOWN=LOW)마다 독립. observation.hours == null 이면 24h 충분 = false(1h 만 판정).
     * 네 상태는 MarketKeys.requireKey 로 검사(공통 규칙).
     */
    fun decide(
        observation: MarketObservation,
        states: ExtremeStates,
        settings: MarketAlertSettings,
        now: Instant,
    ): MarketDecision {
        val pairKey = MarketKeys.pairKey(observation.pair)
        val hourHigh = MarketKeys.extreme(pairKey, ExtremeWindow.ONE_HOUR, MarketDirection.UP)
        val hourLow = MarketKeys.extreme(pairKey, ExtremeWindow.ONE_HOUR, MarketDirection.DOWN)
        val dayHigh = MarketKeys.extreme(pairKey, ExtremeWindow.ONE_DAY, MarketDirection.UP)
        val dayLow = MarketKeys.extreme(pairKey, ExtremeWindow.ONE_DAY, MarketDirection.DOWN)
        MarketKeys.requireKey(states.hourHigh, hourHigh)
        MarketKeys.requireKey(states.hourLow, hourLow)
        MarketKeys.requireKey(states.dayHigh, dayHigh)
        MarketKeys.requireKey(states.dayLow, dayLow)

        val current = observation.current ?: return MarketDecision.NONE
        val minutes = observation.minutes ?: return MarketDecision.NONE
        val hourFrom = now.truncatedTo(ChronoUnit.MINUTES).minus(ExtremeWindow.ONE_HOUR.duration)
        val hourBuckets = minutes.filter { it.observedAt >= hourFrom }
        val hourEnough = MarketEvidence.sufficient(hourBuckets.size, HOUR_MINUTES)
        val hours = observation.hours
        val dayBuckets = if (hours == null) emptyList() else minutes + hours
        val minuteOfHour = now.atZone(ZoneOffset.UTC).minute
        val dayEnough = hours != null &&
            MarketEvidence.sufficient(minutes.size, HOUR_MINUTES + minuteOfHour) &&
            MarketEvidence.sufficient(hours.size, DAY_HOURS)

        val next = mutableListOf<MarketAlertState>()
        val notices = mutableListOf<MarketNotice>()
        for (direction in MarketDirection.entries) {
            val hourState = if (direction == MarketDirection.UP) states.hourHigh else states.hourLow
            val dayState = if (direction == MarketDirection.UP) states.dayHigh else states.dayLow
            val day = broken(dayBuckets.takeIf { dayEnough }, direction, current.premiumRate, settings)
            val hour = broken(hourBuckets.takeIf { hourEnough }, direction, current.premiumRate, settings)
            when {
                day != null -> if (cooledDown(dayState, settings.extremeDayCooldown, now)) {
                    next += dayState.copy(lastNotifiedAt = now)
                    next += hourState.copy(lastNotifiedAt = now)
                    notices += notice(observation, now, ExtremeWindow.ONE_DAY, direction, day)
                }

                hour != null && cooledDown(hourState, settings.extremeHourCooldown, now) -> {
                    next += hourState.copy(lastNotifiedAt = now)
                    notices += notice(observation, now, ExtremeWindow.ONE_HOUR, direction, hour)
                }
            }
        }
        return if (notices.isEmpty()) MarketDecision.NONE else MarketDecision(next, notices)
    }

    /** UP 은 고가 최댓값, DOWN 은 저가 최솟값. */
    private fun reference(buckets: List<PremiumAggregationSnapshot>, direction: MarketDirection): BigDecimal? =
        if (direction == MarketDirection.UP) buckets.maxOfOrNull { it.high } else buckets.minOfOrNull { it.low }

    /** 근거가 충분하고(null 아님) 현재값이 기준을 [MarketAlertSettings.extremeMinDelta] 이상 넘으면 그 기준값. */
    private fun broken(
        buckets: List<PremiumAggregationSnapshot>?,
        direction: MarketDirection,
        rate: BigDecimal,
        settings: MarketAlertSettings,
    ): BigDecimal? {
        val reference = buckets?.let { reference(it, direction) } ?: return null
        val delta = if (direction == MarketDirection.UP) rate - reference else reference - rate
        return reference.takeIf { delta >= settings.extremeMinDelta }
    }

    private fun cooledDown(state: MarketAlertState, cooldown: Duration, now: Instant): Boolean {
        val last = state.lastNotifiedAt ?: return true
        return Duration.between(last, now) >= cooldown
    }

    private fun notice(
        observation: MarketObservation,
        now: Instant,
        window: ExtremeWindow,
        direction: MarketDirection,
        previous: BigDecimal,
    ): MarketNotice.NewExtreme {
        val current = checkNotNull(observation.current)
        return MarketNotice.NewExtreme(
            pair = observation.pair,
            at = now,
            window = window,
            direction = direction,
            previous = previous,
            current = current.premiumRate,
            currentObservedAt = current.observedAt,
        )
    }

    private const val HOUR_MINUTES = 60
    private const val DAY_HOURS = 23
}
