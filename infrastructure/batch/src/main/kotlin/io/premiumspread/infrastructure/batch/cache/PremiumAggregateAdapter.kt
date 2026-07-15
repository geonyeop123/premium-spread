package io.premiumspread.infrastructure.batch.cache

import io.premiumspread.domain.aggregation.AggregationUnit
import io.premiumspread.domain.aggregation.AggregationWindow
import io.premiumspread.domain.aggregation.PremiumAggregatePort
import io.premiumspread.domain.aggregation.PremiumSummaryPeriod
import io.premiumspread.domain.market.MarketPair
import io.premiumspread.domain.premium.PremiumAggregationSnapshot
import io.premiumspread.infrastructure.common.persistence.jdbc.premium.PremiumAggregation
import io.premiumspread.infrastructure.common.persistence.jdbc.premium.PremiumAggregationRepository
import io.premiumspread.redis.AggregationTimeUnit

class PremiumAggregateAdapter(private val cache: PremiumCacheService, private val repository: PremiumAggregationRepository) :
    PremiumAggregatePort {
    override fun aggregate(
        pair: MarketPair,
        sourceUnit: AggregationUnit?,
        window: AggregationWindow,
    ): PremiumAggregationSnapshot? {
        val aggregate = if (sourceUnit == null) {
            cache.aggregateSecondsData(pair, window.from, window.to)
        } else {
            cache.aggregateData(sourceUnit.toPremiumCacheUnit(), pair, window.from, window.to)
        } ?: return null
        return aggregate.toDomain(pair, window.from)
    }

    override fun save(unit: AggregationUnit, window: AggregationWindow, snapshot: PremiumAggregationSnapshot) {
        val aggregate = snapshot.toInfrastructure()
        when (unit) {
            AggregationUnit.MINUTE -> repository.saveMinute(snapshot.pair, window.from, aggregate)

            AggregationUnit.HOUR -> repository.saveHour(snapshot.pair, window.from, aggregate)

            AggregationUnit.DAY -> repository.saveDay(
                snapshot.pair,
                window.from.atZone(window.zone.zoneId).toLocalDate(),
                aggregate,
            )
        }
        cache.saveAggregation(unit.toPremiumCacheUnit(), snapshot.pair, window.from, aggregate)
    }

    override fun calculateSummary(
        pair: MarketPair,
        period: PremiumSummaryPeriod,
        window: AggregationWindow,
    ): PremiumAggregationSnapshot? {
        val sourceUnit = period.sourceUnit
        val summary = if (sourceUnit == null) {
            cache.calculateSummaryFromSeconds(pair, window.from, window.to)
        } else {
            cache.calculateSummary(sourceUnit.toPremiumCacheUnit(), pair, window.from, window.to)
        } ?: return null
        return PremiumAggregationSnapshot(
            pair = pair,
            high = summary.high,
            low = summary.low,
            open = summary.current,
            close = summary.current,
            avg = summary.current,
            count = 1,
            observedAt = summary.currentTimestamp,
            updatedAt = summary.updatedAt,
        )
    }

    override fun saveSummary(period: PremiumSummaryPeriod, snapshot: PremiumAggregationSnapshot) {
        cache.saveSummary(
            interval = period.toCacheInterval(),
            pair = snapshot.pair,
            summary = PremiumCacheService.PremiumSummary(
                high = snapshot.high,
                low = snapshot.low,
                current = snapshot.close,
                currentTimestamp = snapshot.observedAt,
                updatedAt = snapshot.updatedAt,
            ),
        )
    }

    private fun AggregationUnit.toPremiumCacheUnit(): AggregationTimeUnit = when (this) {
        AggregationUnit.MINUTE -> AggregationTimeUnit.MINUTES
        AggregationUnit.HOUR -> AggregationTimeUnit.HOURS
        AggregationUnit.DAY -> AggregationTimeUnit.DAYS
    }

    private fun PremiumSummaryPeriod.toCacheInterval(): String = when (this) {
        PremiumSummaryPeriod.ONE_MINUTE -> "1m"
        PremiumSummaryPeriod.TEN_MINUTES -> "10m"
        PremiumSummaryPeriod.ONE_HOUR -> "1h"
        PremiumSummaryPeriod.ONE_DAY -> "1d"
    }

    private fun PremiumAggregation.toDomain(pair: MarketPair, observedAt: java.time.Instant) = PremiumAggregationSnapshot(
        pair = pair,
        high = high,
        low = low,
        open = open,
        close = close,
        avg = avg,
        count = count,
        observedAt = observedAt,
        fxRate = fxRate,
    )

    private fun PremiumAggregationSnapshot.toInfrastructure() = PremiumAggregation(
        symbol = pair.symbol.code,
        high = high,
        low = low,
        open = open,
        close = close,
        avg = avg,
        count = count,
        fxRate = fxRate,
    )
}
