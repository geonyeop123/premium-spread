package io.premiumspread.domain.ownermarket

import io.premiumspread.domain.aggregation.AggregationWindow
import io.premiumspread.domain.aggregation.AggregationZone
import io.premiumspread.domain.market.MarketPair
import io.premiumspread.domain.premium.PremiumAggregationSnapshot
import io.premiumspread.domain.premium.PremiumSnapshot
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** 한 회차가 읽는 창. 모두 [from, to). */
data class MarketWindows(
    /** 분 bucket: [floorHour(now) - 1h, floorMinute(now)) - 60~119개. 급변·1h·24h 최근부 공용. */
    val minutes: AggregationWindow,
    /** 시 bucket: [floorHour(now) - 24h, floorHour(now) - 1h) - 23개. 24h 과거부. */
    val hours: AggregationWindow,
    /** 전일: [zone 오늘 00:00 - 1일, zone 오늘 00:00). 요약 전용. */
    val previousDay: AggregationWindow,
) {
    companion object {
        fun of(now: Instant, zone: ZoneId): MarketWindows {
            val aggregationZone = AggregationZone.of(zone.id)
            val hour = now.truncatedTo(ChronoUnit.HOURS)
            val today = now.atZone(zone).toLocalDate()
            val oneHourAgo = hour.minus(Duration.ofHours(1))
            val yesterdayStart = today.minusDays(1).atStartOfDay(zone).toInstant()
            val todayStart = today.atStartOfDay(zone).toInstant()
            return MarketWindows(
                minutes = AggregationWindow(oneHourAgo, now.truncatedTo(ChronoUnit.MINUTES), aggregationZone),
                hours = AggregationWindow(hour.minus(Duration.ofHours(24)), oneHourAgo, aggregationZone),
                previousDay = AggregationWindow(yesterdayStart, todayStart, aggregationZone),
            )
        }
    }
}

/** 한 회차의 시장 관측. null = 그 원천을 모름(없음·오래됨·읽기 실패·pair 불일치). */
data class MarketObservation(
    val pair: MarketPair,
    val current: PremiumSnapshot?,
    val minutes: List<PremiumAggregationSnapshot>?,
    val hours: List<PremiumAggregationSnapshot>?,
)

object MarketEvidence {
    /** 현재값의 최대 나이 - 거래 준비 평가와 같은 10초. */
    val CURRENT_MAX_AGE: Duration = Duration.ofSeconds(10)

    /** now - observedAt (음수면 0) <= CURRENT_MAX_AGE 이고 pair 가 같을 때만 현재값으로 쓴다. 아니면 null. */
    fun current(snapshot: PremiumSnapshot?, pair: MarketPair, now: Instant): PremiumSnapshot? {
        if (snapshot == null || snapshot.pair != pair) return null
        val age = Duration.between(snapshot.observedAt, now).coerceAtLeast(Duration.ZERO)
        return snapshot.takeIf { age <= CURRENT_MAX_AGE }
    }

    /** [actual] >= ceil(expected x 0.8) 이면 충분. expected >= 1. */
    fun sufficient(actual: Int, expected: Int): Boolean {
        require(expected >= 1) { "expected must be at least 1." }
        return actual.toLong() * 5 >= expected.toLong() * 4
    }
}
