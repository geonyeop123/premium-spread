package io.premiumspread.infrastructure.batch.cache

import io.premiumspread.domain.market.MarketPair
import io.premiumspread.domain.premium.PremiumSnapshot
import io.premiumspread.domain.ticker.Symbol
import io.premiumspread.infrastructure.common.cache.CacheReadMetrics
import io.premiumspread.infrastructure.common.cache.CacheReadOutcome
import io.premiumspread.infrastructure.common.cache.shortenTtl
import io.premiumspread.infrastructure.common.cache.premium.PremiumAggregationCacheReader
import io.premiumspread.infrastructure.common.persistence.jdbc.premium.PremiumAggregation
import io.premiumspread.redis.AggregationTimeUnit
import io.premiumspread.redis.RedisKeyGenerator
import io.premiumspread.redis.RedisTtl
import io.premiumspread.redis.support.TimeSeriesCacheSupport
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ZSetOperations.TypedTuple
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.Instant

interface PremiumSecondsCacheOperations {
    fun saveToSeconds(snapshot: PremiumSnapshot)

    fun getSecondsData(symbol: String, from: Instant, to: Instant): List<Pair<Instant, BigDecimal>>

    fun getSecondsData(pair: MarketPair, from: Instant, to: Instant): List<Pair<Instant, BigDecimal>>

    fun getSecondsDataFull(symbol: String, from: Instant, to: Instant): List<PremiumCacheService.SecondsEntry>

    fun getSecondsDataFull(pair: MarketPair, from: Instant, to: Instant): List<PremiumCacheService.SecondsEntry>
}

interface PremiumAggregationCacheOperations {
    fun saveAggregation(timeUnit: AggregationTimeUnit, symbol: String, timestamp: Instant, agg: PremiumAggregation)

    fun saveAggregation(timeUnit: AggregationTimeUnit, pair: MarketPair, timestamp: Instant, agg: PremiumAggregation)

    fun getAggregationData(
        timeUnit: AggregationTimeUnit,
        symbol: String,
        from: Instant,
        to: Instant,
    ): List<Pair<Instant, PremiumAggregation>>

    fun getAggregationData(
        timeUnit: AggregationTimeUnit,
        pair: MarketPair,
        from: Instant,
        to: Instant,
    ): List<Pair<Instant, PremiumAggregation>>

    fun aggregateSecondsData(symbol: String, from: Instant, to: Instant): PremiumAggregation?

    fun aggregateSecondsData(pair: MarketPair, from: Instant, to: Instant): PremiumAggregation?

    fun aggregateData(timeUnit: AggregationTimeUnit, symbol: String, from: Instant, to: Instant): PremiumAggregation?

    fun aggregateData(timeUnit: AggregationTimeUnit, pair: MarketPair, from: Instant, to: Instant): PremiumAggregation?
}

interface PremiumSummaryCacheOperations {
    fun saveSummary(interval: String, symbol: String, summary: PremiumCacheService.PremiumSummary)

    fun saveSummary(interval: String, pair: MarketPair, summary: PremiumCacheService.PremiumSummary)

    fun getSummary(interval: String, symbol: String): PremiumCacheService.PremiumSummary?

    fun getSummary(interval: String, pair: MarketPair): PremiumCacheService.PremiumSummary?

    fun calculateSummaryFromSeconds(symbol: String, from: Instant, to: Instant): PremiumCacheService.PremiumSummary?

    fun calculateSummaryFromSeconds(pair: MarketPair, from: Instant, to: Instant): PremiumCacheService.PremiumSummary?

    fun calculateSummary(
        timeUnit: AggregationTimeUnit,
        symbol: String,
        from: Instant,
        to: Instant,
    ): PremiumCacheService.PremiumSummary?

    fun calculateSummary(
        timeUnit: AggregationTimeUnit,
        pair: MarketPair,
        from: Instant,
        to: Instant,
    ): PremiumCacheService.PremiumSummary?
}

@Component
class PremiumSecondsCacheOperationsImpl(
    private val redisTemplate: StringRedisTemplate,
    private val timeSeriesCache: TimeSeriesCacheSupport,
    private val metrics: CacheReadMetrics,
) : PremiumSecondsCacheOperations {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun saveToSeconds(snapshot: PremiumSnapshot) {
        val pair = snapshot.pair
        val key = RedisKeyGenerator.premiumV2SecondsKey(pair.koreaExchange.name, pair.foreignExchange.name, pair.symbol.code)
        val value = "${snapshot.premiumRate}:${snapshot.koreaPrice}:${snapshot.foreignPrice}:${snapshot.fxRate}"
        timeSeriesCache.add(key, value, snapshot.observedAt, RedisTtl.SECONDS_DATA)
        log.debug("Saved premium to seconds ZSet: {} = {}%", key, snapshot.premiumRate)
    }

    override fun getSecondsData(symbol: String, from: Instant, to: Instant): List<Pair<Instant, BigDecimal>> =
        getSecondsData(MarketPair.default(Symbol(symbol)), from, to)

    override fun getSecondsData(pair: MarketPair, from: Instant, to: Instant): List<Pair<Instant, BigDecimal>> =
        getSecondsDataFull(pair, from, to).map { it.timestamp to it.rate }

    override fun getSecondsDataFull(symbol: String, from: Instant, to: Instant): List<PremiumCacheService.SecondsEntry> =
        getSecondsDataFull(MarketPair.default(Symbol(symbol)), from, to)

    override fun getSecondsDataFull(
        pair: MarketPair,
        from: Instant,
        to: Instant,
    ): List<PremiumCacheService.SecondsEntry> {
        val v2Key = RedisKeyGenerator.premiumV2SecondsKey(
            pair.koreaExchange.name,
            pair.foreignExchange.name,
            pair.symbol.code,
        )
        val entries = readTimeSeries(v2Key, from, to)
        return when {
            entries == null -> emptyList()

            entries.isNotEmpty() -> parseSeconds(v2Key, entries).orEmpty().also { parsed ->
                if (parsed.isNotEmpty()) metrics.record(CACHE_NAME, CacheReadOutcome.HIT)
            }

            else -> readLegacySeconds(pair, from, to)
        }
    }

    private fun readLegacySeconds(pair: MarketPair, from: Instant, to: Instant): List<PremiumCacheService.SecondsEntry> {
        metrics.record(CACHE_NAME, CacheReadOutcome.MISS)
        if (pair != MarketPair.default(pair.symbol)) return emptyList()

        val legacyKey = RedisKeyGenerator.premiumSecondsKey(pair.symbol.code)
        val entries = readTimeSeries(legacyKey, from, to)
        return when {
            entries.isNullOrEmpty() -> emptyList()

            else -> {
                redisTemplate.shortenTtl(legacyKey, RedisTtl.PREMIUM_LEGACY_READ_WINDOW)
                parseSeconds(legacyKey, entries).orEmpty().also { parsed ->
                    if (parsed.isNotEmpty()) metrics.record(CACHE_NAME, CacheReadOutcome.LEGACY_HIT)
                }
            }
        }
    }

    private fun readTimeSeries(key: String, from: Instant, to: Instant): List<TypedTuple<String>>? = try {
        timeSeriesCache.rangeByTime(key, from, to)
    } catch (exception: DataAccessException) {
        log.warn("Premium time-series cache read failed: {}", key, exception)
        metrics.record(CACHE_NAME, CacheReadOutcome.ERROR)
        null
    }

    private fun parseSeconds(key: String, entries: List<TypedTuple<String>>): List<PremiumCacheService.SecondsEntry>? {
        val parsed = entries.map { entry ->
            val parts = entry.value?.split(":") ?: return corruptSeconds(key)
            if (parts.size != 4 || parts.any { it.toBigDecimalOrNull() == null }) return corruptSeconds(key)
            val timestamp = timeSeriesCache.extractTimestamp(entry) ?: return corruptSeconds(key)
            PremiumCacheService.SecondsEntry(timestamp, parts[0].toBigDecimal(), parts[3].toBigDecimal())
        }
        return parsed
    }

    private fun corruptSeconds(key: String): List<PremiumCacheService.SecondsEntry>? {
        log.warn("Corrupt premium seconds cache entry: {}", key)
        metrics.record(CACHE_NAME, CacheReadOutcome.CORRUPT)
        return null
    }

    private companion object {
        const val CACHE_NAME = "premium_seconds"
    }
}

@Component
class PremiumAggregationCacheOperationsImpl(
    private val timeSeriesCache: TimeSeriesCacheSupport,
    private val aggregationCacheReader: PremiumAggregationCacheReader,
    private val secondsCache: PremiumSecondsCacheOperations,
) : PremiumAggregationCacheOperations {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun saveAggregation(
        timeUnit: AggregationTimeUnit,
        symbol: String,
        timestamp: Instant,
        agg: PremiumAggregation,
    ) = saveAggregation(timeUnit, MarketPair.default(Symbol(symbol)), timestamp, agg)

    override fun saveAggregation(
        timeUnit: AggregationTimeUnit,
        pair: MarketPair,
        timestamp: Instant,
        agg: PremiumAggregation,
    ) {
        val key = RedisKeyGenerator.premiumV2AggregationKey(
            pair.koreaExchange.name,
            pair.foreignExchange.name,
            pair.symbol.code,
            timeUnit.name,
        )
        val fxPart = agg.fxRate?.toPlainString() ?: ""
        val value = "${agg.high}:${agg.low}:${agg.open}:${agg.close}:${agg.avg}:${agg.count}:$fxPart"
        timeSeriesCache.add(key, value, timestamp, timeUnit.ttl)
        log.debug("Saved aggregation to {}: {} at {}", timeUnit, pair, timestamp)
    }

    override fun getAggregationData(
        timeUnit: AggregationTimeUnit,
        symbol: String,
        from: Instant,
        to: Instant,
    ): List<Pair<Instant, PremiumAggregation>> =
        getAggregationData(timeUnit, MarketPair.default(Symbol(symbol)), from, to)

    override fun getAggregationData(
        timeUnit: AggregationTimeUnit,
        pair: MarketPair,
        from: Instant,
        to: Instant,
    ): List<Pair<Instant, PremiumAggregation>> {
        val interval = when (timeUnit) {
            AggregationTimeUnit.MINUTES -> "1m"
            AggregationTimeUnit.HOURS -> "1h"
            AggregationTimeUnit.DAYS -> "1d"
            AggregationTimeUnit.SECONDS -> return emptyList()
        }
        return aggregationCacheReader.findByInterval(pair, interval, from, to)
            ?.map { snapshot -> snapshot.observedAt to snapshot.toPremiumAggregation() }
            .orEmpty()
    }

    override fun aggregateSecondsData(symbol: String, from: Instant, to: Instant): PremiumAggregation? =
        aggregateSecondsData(MarketPair.default(Symbol(symbol)), from, to)

    override fun aggregateSecondsData(pair: MarketPair, from: Instant, to: Instant): PremiumAggregation? {
        val data = secondsCache.getSecondsDataFull(pair, from, to)
        if (data.isEmpty()) return null
        val rates = data.map { it.rate }
        return PremiumAggregation(
            symbol = pair.symbol.code,
            high = rates.max(),
            low = rates.min(),
            open = rates.first(),
            close = rates.last(),
            avg = rates.fold(BigDecimal.ZERO, BigDecimal::add)
                .divide(rates.size.toBigDecimal(), 4, RoundingMode.HALF_UP),
            count = rates.size,
            fxRate = data.last().fxRate,
        )
    }

    override fun aggregateData(
        timeUnit: AggregationTimeUnit,
        symbol: String,
        from: Instant,
        to: Instant,
    ): PremiumAggregation? = aggregateData(timeUnit, MarketPair.default(Symbol(symbol)), from, to)

    override fun aggregateData(
        timeUnit: AggregationTimeUnit,
        pair: MarketPair,
        from: Instant,
        to: Instant,
    ): PremiumAggregation? {
        val data = getAggregationData(timeUnit, pair, from, to)
        if (data.isEmpty()) return null
        val aggregations = data.map { it.second }
        val totalCount = aggregations.sumOf { it.count }
        return PremiumAggregation(
            symbol = pair.symbol.code,
            high = aggregations.maxOf { it.high },
            low = aggregations.minOf { it.low },
            open = aggregations.first().open,
            close = aggregations.last().close,
            avg = aggregations.sumOf { it.avg * it.count.toBigDecimal() }
                .divide(totalCount.toBigDecimal(), 4, RoundingMode.HALF_UP),
            count = totalCount,
            fxRate = aggregations.last().fxRate,
        )
    }

    private fun io.premiumspread.domain.premium.PremiumAggregationSnapshot.toPremiumAggregation() = PremiumAggregation(
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

@Component
class PremiumSummaryCacheOperationsImpl(
    private val redisTemplate: StringRedisTemplate,
    private val clock: Clock,
    private val secondsCache: PremiumSecondsCacheOperations,
    private val aggregationCache: PremiumAggregationCacheOperations,
    private val metrics: CacheReadMetrics,
) : PremiumSummaryCacheOperations {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun saveSummary(interval: String, symbol: String, summary: PremiumCacheService.PremiumSummary) {
        saveSummary(interval, MarketPair.default(Symbol(symbol)), summary)
    }

    override fun saveSummary(interval: String, pair: MarketPair, summary: PremiumCacheService.PremiumSummary) {
        val key = RedisKeyGenerator.premiumV2SummaryKey(
            pair.koreaExchange.name,
            pair.foreignExchange.name,
            pair.symbol.code,
            interval,
        )
        val hash = mapOf(
            "high" to summary.high.toPlainString(),
            "low" to summary.low.toPlainString(),
            "current" to summary.current.toPlainString(),
            "current_ts" to summary.currentTimestamp.toEpochMilli().toString(),
            "updated_at" to summary.updatedAt.toEpochMilli().toString(),
        )
        redisTemplate.opsForHash<String, String>().putAll(key, hash)
        redisTemplate.expire(key, summaryTtl(interval))
        log.debug("Saved summary cache: {} (high={}, low={}, current={})", key, summary.high, summary.low, summary.current)
    }

    override fun getSummary(interval: String, symbol: String): PremiumCacheService.PremiumSummary? =
        getSummary(interval, MarketPair.default(Symbol(symbol)))

    override fun getSummary(interval: String, pair: MarketPair): PremiumCacheService.PremiumSummary? {
        val v2Key = RedisKeyGenerator.premiumV2SummaryKey(
            pair.koreaExchange.name,
            pair.foreignExchange.name,
            pair.symbol.code,
            interval,
        )
        val hash = readSummaryHash(v2Key)
        return when {
            hash == null -> null
            hash.isNotEmpty() -> parseSummary(v2Key, hash, CacheReadOutcome.HIT)
            else -> readLegacySummary(interval, pair)
        }
    }

    override fun calculateSummaryFromSeconds(
        symbol: String,
        from: Instant,
        to: Instant,
    ): PremiumCacheService.PremiumSummary? =
        calculateSummaryFromSeconds(MarketPair.default(Symbol(symbol)), from, to)

    override fun calculateSummaryFromSeconds(
        pair: MarketPair,
        from: Instant,
        to: Instant,
    ): PremiumCacheService.PremiumSummary? {
        val data = secondsCache.getSecondsData(pair, from, to)
        if (data.isEmpty()) return null
        val rates = data.map { it.second }
        val (currentTimestamp, current) = data.last()
        return PremiumCacheService.PremiumSummary(
            high = rates.max(),
            low = rates.min(),
            current = current,
            currentTimestamp = currentTimestamp,
            updatedAt = clock.instant(),
        )
    }

    override fun calculateSummary(
        timeUnit: AggregationTimeUnit,
        symbol: String,
        from: Instant,
        to: Instant,
    ): PremiumCacheService.PremiumSummary? =
        calculateSummary(timeUnit, MarketPair.default(Symbol(symbol)), from, to)

    override fun calculateSummary(
        timeUnit: AggregationTimeUnit,
        pair: MarketPair,
        from: Instant,
        to: Instant,
    ): PremiumCacheService.PremiumSummary? {
        val data = aggregationCache.getAggregationData(timeUnit, pair, from, to)
        if (data.isEmpty()) return null
        val (currentTimestamp, currentAggregation) = data.last()
        return PremiumCacheService.PremiumSummary(
            high = data.maxOf { it.second.high },
            low = data.minOf { it.second.low },
            current = currentAggregation.close,
            currentTimestamp = currentTimestamp,
            updatedAt = clock.instant(),
        )
    }

    private fun readLegacySummary(interval: String, pair: MarketPair): PremiumCacheService.PremiumSummary? {
        metrics.record(CACHE_NAME, CacheReadOutcome.MISS)
        if (pair != MarketPair.default(pair.symbol)) return null
        val legacyKey = RedisKeyGenerator.summaryKey(interval, pair.symbol.code)
        val hash = readSummaryHash(legacyKey)
        return when {
            hash.isNullOrEmpty() -> null

            else -> {
                redisTemplate.shortenTtl(legacyKey, RedisTtl.PREMIUM_LEGACY_READ_WINDOW)
                parseSummary(legacyKey, hash, CacheReadOutcome.LEGACY_HIT)
            }
        }
    }

    private fun readSummaryHash(key: String): Map<String, String>? = try {
        redisTemplate.opsForHash<String, String>().entries(key)
    } catch (exception: DataAccessException) {
        log.warn("Premium summary cache read failed: {}", key, exception)
        metrics.record(CACHE_NAME, CacheReadOutcome.ERROR)
        null
    }

    private fun parseSummary(
        key: String,
        hash: Map<String, String>,
        success: CacheReadOutcome,
    ): PremiumCacheService.PremiumSummary? {
        val summary = PremiumCacheService.PremiumSummary(
            high = hash["high"]?.toBigDecimalOrNull() ?: return corruptSummary(key),
            low = hash["low"]?.toBigDecimalOrNull() ?: return corruptSummary(key),
            current = hash["current"]?.toBigDecimalOrNull() ?: return corruptSummary(key),
            currentTimestamp = hash["current_ts"]?.toLongOrNull()?.let(Instant::ofEpochMilli)
                ?: return corruptSummary(key),
            updatedAt = hash["updated_at"]?.toLongOrNull()?.let(Instant::ofEpochMilli)
                ?: return corruptSummary(key),
        )
        metrics.record(CACHE_NAME, success)
        return summary
    }

    private fun corruptSummary(key: String): PremiumCacheService.PremiumSummary? {
        log.warn("Corrupt premium summary cache payload: {}", key)
        metrics.record(CACHE_NAME, CacheReadOutcome.CORRUPT)
        return null
    }

    private fun summaryTtl(interval: String) = when (interval) {
        "1m" -> RedisTtl.Summary.ONE_MINUTE
        "10m" -> RedisTtl.Summary.TEN_MINUTES
        "1h" -> RedisTtl.Summary.ONE_HOUR
        "1d" -> RedisTtl.Summary.ONE_DAY
        else -> RedisTtl.Summary.ONE_MINUTE
    }

    private companion object {
        const val CACHE_NAME = "premium_summary"
    }
}
