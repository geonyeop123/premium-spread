package io.premiumspread.domain.ownermarket

import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

object PremiumSwingPolicy {
    /**
     * design 7.2(A) W1~W4. [up]·[down] 은 각각 swing-up·swing-down key 의 상태여야 한다(검사는 W1 보다 먼저).
     * 기준은 창 안 분봉 종가 - 고가·저가는 1초 튀는 값 하나로 급변을 만들기 때문이다. 방향마다 독립이다.
     */
    fun decide(
        observation: MarketObservation,
        up: MarketAlertState,
        down: MarketAlertState,
        settings: MarketAlertSettings,
        now: Instant,
    ): MarketDecision {
        val pairKey = MarketKeys.pairKey(observation.pair)
        MarketKeys.requireKey(up, MarketKeys.swing(pairKey, MarketDirection.UP))
        MarketKeys.requireKey(down, MarketKeys.swing(pairKey, MarketDirection.DOWN))

        val current = observation.current ?: return MarketDecision.NONE
        val minutes = observation.minutes ?: return MarketDecision.NONE
        val from = now.truncatedTo(ChronoUnit.MINUTES).minus(settings.swingWindow)
        val closes = minutes.filter { it.observedAt >= from }.map { it.close }
        if (!MarketEvidence.sufficient(closes.size, settings.swingWindow.toMinutes().toInt())) {
            return MarketDecision.NONE
        }

        val rate = current.premiumRate
        val low = closes.min()
        val high = closes.max()
        val next = mutableListOf<MarketAlertState>()
        val notices = mutableListOf<MarketNotice>()

        if (rate - low >= settings.swingThreshold && cooledDown(up, settings.swingCooldown, now)) {
            next += up.copy(lastNotifiedAt = now)
            notices += notice(observation, now, MarketDirection.UP, settings, low, rate)
        }
        if (high - rate >= settings.swingThreshold && cooledDown(down, settings.swingCooldown, now)) {
            next += down.copy(lastNotifiedAt = now)
            notices += notice(observation, now, MarketDirection.DOWN, settings, high, rate)
        }
        return if (notices.isEmpty()) MarketDecision.NONE else MarketDecision(next, notices)
    }

    private fun cooledDown(state: MarketAlertState, cooldown: Duration, now: Instant): Boolean {
        val last = state.lastNotifiedAt ?: return true
        return Duration.between(last, now) >= cooldown
    }

    private fun notice(
        observation: MarketObservation,
        now: Instant,
        direction: MarketDirection,
        settings: MarketAlertSettings,
        reference: BigDecimal,
        rate: BigDecimal,
    ): MarketNotice.Swing = MarketNotice.Swing(
        pair = observation.pair,
        at = now,
        direction = direction,
        window = settings.swingWindow,
        reference = reference,
        current = rate,
        currentObservedAt = checkNotNull(observation.current).observedAt,
        delta = rate - reference,
    )
}
