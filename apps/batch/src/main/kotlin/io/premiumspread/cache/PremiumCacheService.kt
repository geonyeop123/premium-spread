package io.premiumspread.cache

import io.premiumspread.redis.AggregationTimeUnit
import io.premiumspread.domain.market.MarketPair
import io.premiumspread.domain.premium.PremiumSnapshot
import io.premiumspread.domain.ticker.Exchange
import io.premiumspread.domain.ticker.Symbol
import io.premiumspread.infrastructure.common.cache.premium.PremiumCacheReader
import io.premiumspread.infrastructure.common.cache.premium.PremiumCacheWriter
import io.premiumspread.infrastructure.common.cache.premium.PremiumAggregationCacheReader
import io.premiumspread.infrastructure.common.cache.CacheReadMetrics
import io.premiumspread.infrastructure.common.cache.CacheReadOutcome
import io.premiumspread.infrastructure.common.cache.shortenTtl
import io.premiumspread.redis.RedisKeyGenerator
import io.premiumspread.redis.RedisTtl
import io.premiumspread.redis.support.TimeSeriesCacheSupport
import io.premiumspread.infrastructure.common.persistence.jdbc.premium.PremiumAggregation
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ZSetOperations.TypedTuple
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.Clock

/**
 * 프리미엄 캐시 데이터
 */
data class PremiumCacheData(
    val premiumRate: BigDecimal,
    val koreaPrice: BigDecimal,
    val foreignPrice: BigDecimal,
    val foreignPriceInKrw: BigDecimal,
    val fxRate: BigDecimal,
    val observedAt: Instant,
    val pair: MarketPair,
    val fxSource: Exchange = Exchange.FX_PROVIDER,
    val fxObservedAt: Instant = observedAt,
) {
    val symbol: String
        get() = pair.symbol.code

    companion object {
        fun from(snapshot: PremiumSnapshot): PremiumCacheData = PremiumCacheData(
            premiumRate = snapshot.premiumRate,
            koreaPrice = snapshot.koreaPrice,
            foreignPrice = snapshot.foreignPrice,
            foreignPriceInKrw = snapshot.foreignPriceInKrw,
            fxRate = snapshot.fxRate,
            observedAt = snapshot.observedAt,
            pair = snapshot.pair,
            fxSource = snapshot.fxSource,
            fxObservedAt = snapshot.fxObservedAt,
        )
    }
}

@Service
class PremiumCacheService(
    private val redisTemplate: StringRedisTemplate,
    private val timeSeriesCache: TimeSeriesCacheSupport,
    private val clock: Clock,
    private val cacheReader: PremiumCacheReader,
    private val cacheWriter: PremiumCacheWriter,
    private val aggregationCacheReader: PremiumAggregationCacheReader,
    private val metrics: CacheReadMetrics,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 프리미엄 데이터 저장
     */
    fun save(snapshot: PremiumSnapshot) {
        cacheWriter.save(snapshot)
    }

    /**
     * 프리미엄 히스토리 저장 (Sorted Set)
     */
    fun saveHistory(snapshot: PremiumSnapshot) {
        cacheWriter.saveHistory(snapshot)
    }

    /**
     * 프리미엄 데이터 조회
     */
    fun get(symbol: String): PremiumCacheData? {
        return get(MarketPair.default(Symbol(symbol)))
    }

    fun get(pair: MarketPair): PremiumCacheData? {
        val cached = cacheReader.get(pair) ?: return null
        return PremiumCacheData(
            cached.premiumRate,
            cached.koreaPrice,
            cached.foreignPrice,
            cached.foreignPriceInKrw,
            cached.fxRate,
            cached.observedAt,
            cached.pair,
            cached.fxSource,
            cached.fxObservedAt,
        )
    }

    // ========== 초당 데이터 ZSet 저장 ==========

    /**
     * 초당 데이터 ZSet에 저장 (DB INSERT 대체)
     */
    fun saveToSeconds(snapshot: PremiumSnapshot) {
        val pair = snapshot.pair
        val key = RedisKeyGenerator.premiumV2SecondsKey(pair.koreaExchange.name, pair.foreignExchange.name, pair.symbol.code)
        val value = "${snapshot.premiumRate}:${snapshot.koreaPrice}:${snapshot.foreignPrice}:${snapshot.fxRate}"

        timeSeriesCache.add(key, value, snapshot.observedAt, RedisTtl.SECONDS_DATA)

        log.debug("Saved premium to seconds ZSet: {} = {}%", key, snapshot.premiumRate)
    }

    /**
     * 초당 데이터 조회 (시간 범위)
     */
    data class SecondsEntry(
        val timestamp: Instant,
        val rate: BigDecimal,
        val fxRate: BigDecimal?,
    )

    fun getSecondsData(symbol: String, from: Instant, to: Instant): List<Pair<Instant, BigDecimal>> {
        return getSecondsData(MarketPair.default(Symbol(symbol)), from, to)
    }

    fun getSecondsData(pair: MarketPair, from: Instant, to: Instant): List<Pair<Instant, BigDecimal>> =
        getSecondsDataFull(pair, from, to).map { it.timestamp to it.rate }

    fun getSecondsDataFull(symbol: String, from: Instant, to: Instant): List<SecondsEntry> =
        getSecondsDataFull(MarketPair.default(Symbol(symbol)), from, to)

    fun getSecondsDataFull(pair: MarketPair, from: Instant, to: Instant): List<SecondsEntry> {
        val v2Key = RedisKeyGenerator.premiumV2SecondsKey(
            pair.koreaExchange.name,
            pair.foreignExchange.name,
            pair.symbol.code,
        )
        val v2Entries = readTimeSeries(v2Key, SECONDS_CACHE_NAME, from, to) ?: return emptyList()
        if (v2Entries.isNotEmpty()) {
            return parseSeconds(v2Key, v2Entries)?.also {
                metrics.record(SECONDS_CACHE_NAME, CacheReadOutcome.HIT)
            } ?: emptyList()
        }

        metrics.record(SECONDS_CACHE_NAME, CacheReadOutcome.MISS)
        if (pair != MarketPair.default(pair.symbol)) return emptyList()
        val legacyKey = RedisKeyGenerator.premiumSecondsKey(pair.symbol.code)
        val legacyEntries = readTimeSeries(legacyKey, SECONDS_CACHE_NAME, from, to) ?: return emptyList()
        if (legacyEntries.isEmpty()) return emptyList()
        redisTemplate.shortenTtl(legacyKey, RedisTtl.PREMIUM_LEGACY_READ_WINDOW)
        return parseSeconds(legacyKey, legacyEntries)?.also {
            metrics.record(SECONDS_CACHE_NAME, CacheReadOutcome.LEGACY_HIT)
        } ?: emptyList()
    }

    // ========== 통합 집계 데이터 저장/조회 ==========

    /**
     * 집계 데이터 ZSet에 저장 (통합)
     */
    fun saveAggregation(
        timeUnit: AggregationTimeUnit,
        symbol: String,
        timestamp: Instant,
        agg: PremiumAggregation,
    ) = saveAggregation(timeUnit, MarketPair.default(Symbol(symbol)), timestamp, agg)

    fun saveAggregation(
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
        val value = "${agg.high}:${agg.low}:${agg.open}:${agg.close}:${agg.avg}:${agg.count}:${fxPart}"

        timeSeriesCache.add(key, value, timestamp, timeUnit.ttl)

        log.debug("Saved aggregation to {}: {} at {}", timeUnit, pair, timestamp)
    }

    /**
     * 집계 데이터 조회 (통합)
     */
    fun getAggregationData(
        timeUnit: AggregationTimeUnit,
        symbol: String,
        from: Instant,
        to: Instant,
    ): List<Pair<Instant, PremiumAggregation>> =
        getAggregationData(timeUnit, MarketPair.default(Symbol(symbol)), from, to)

    fun getAggregationData(
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
            ?.map { snapshot ->
                snapshot.observedAt to PremiumAggregation(
                    symbol = snapshot.pair.symbol.code,
                    high = snapshot.high,
                    low = snapshot.low,
                    open = snapshot.open,
                    close = snapshot.close,
                    avg = snapshot.avg,
                    count = snapshot.count,
                    fxRate = snapshot.fxRate,
                )
            }
            ?: emptyList()
    }

    // ========== 서머리 캐시 ==========

    /**
     * 서머리 데이터
     */
    data class PremiumSummary(
        val high: BigDecimal,
        val low: BigDecimal,
        val current: BigDecimal,
        val currentTimestamp: Instant,
        val updatedAt: Instant,
    )

    /**
     * 서머리 캐시 저장
     */
    fun saveSummary(interval: String, symbol: String, summary: PremiumSummary) {
        saveSummary(interval, MarketPair.default(Symbol(symbol)), summary)
    }

    fun saveSummary(interval: String, pair: MarketPair, summary: PremiumSummary) {
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

        val ttl = when (interval) {
            "1m" -> RedisTtl.Summary.ONE_MINUTE
            "10m" -> RedisTtl.Summary.TEN_MINUTES
            "1h" -> RedisTtl.Summary.ONE_HOUR
            "1d" -> RedisTtl.Summary.ONE_DAY
            else -> RedisTtl.Summary.ONE_MINUTE
        }
        redisTemplate.expire(key, ttl)

        log.debug("Saved summary cache: {} (high={}, low={}, current={})", key, summary.high, summary.low, summary.current)
    }

    /**
     * 서머리 캐시 조회
     */
    fun getSummary(interval: String, symbol: String): PremiumSummary? {
        return getSummary(interval, MarketPair.default(Symbol(symbol)))
    }

    fun getSummary(interval: String, pair: MarketPair): PremiumSummary? {
        val v2Key = RedisKeyGenerator.premiumV2SummaryKey(
            pair.koreaExchange.name,
            pair.foreignExchange.name,
            pair.symbol.code,
            interval,
        )

        val v2Hash = readSummaryHash(v2Key) ?: return null
        if (v2Hash.isNotEmpty()) return parseSummary(v2Key, v2Hash, CacheReadOutcome.HIT)

        metrics.record(SUMMARY_CACHE_NAME, CacheReadOutcome.MISS)
        if (pair != MarketPair.default(pair.symbol)) return null
        val legacyKey = RedisKeyGenerator.summaryKey(interval, pair.symbol.code)
        val legacyHash = readSummaryHash(legacyKey) ?: return null
        if (legacyHash.isEmpty()) return null
        redisTemplate.shortenTtl(legacyKey, RedisTtl.PREMIUM_LEGACY_READ_WINDOW)
        return parseSummary(legacyKey, legacyHash, CacheReadOutcome.LEGACY_HIT)
    }

    private fun readTimeSeries(
        key: String,
        cacheName: String,
        from: Instant,
        to: Instant,
    ): List<TypedTuple<String>>? = try {
        timeSeriesCache.rangeByTime(key, from, to)
    } catch (exception: DataAccessException) {
        log.warn("Premium time-series cache read failed: {}", key, exception)
        metrics.record(cacheName, CacheReadOutcome.ERROR)
        null
    }

    private fun parseSeconds(key: String, entries: List<TypedTuple<String>>): List<SecondsEntry>? {
        val parsed = entries.map { entry ->
            val parts = entry.value?.split(":") ?: return corruptSeconds(key)
            if (parts.size != 4 || parts.any { it.toBigDecimalOrNull() == null }) return corruptSeconds(key)
            val rate = parts[0].toBigDecimal()
            val fxRate = parts[3].toBigDecimal()
            val timestamp = timeSeriesCache.extractTimestamp(entry) ?: return corruptSeconds(key)
            SecondsEntry(timestamp, rate, fxRate)
        }
        return parsed
    }

    private fun corruptSeconds(key: String): List<SecondsEntry>? {
        log.warn("Corrupt premium seconds cache entry: {}", key)
        metrics.record(SECONDS_CACHE_NAME, CacheReadOutcome.CORRUPT)
        return null
    }

    private fun readSummaryHash(key: String): Map<String, String>? = try {
        redisTemplate.opsForHash<String, String>().entries(key)
    } catch (exception: DataAccessException) {
        log.warn("Premium summary cache read failed: {}", key, exception)
        metrics.record(SUMMARY_CACHE_NAME, CacheReadOutcome.ERROR)
        null
    }

    private fun parseSummary(
        key: String,
        hash: Map<String, String>,
        success: CacheReadOutcome,
    ): PremiumSummary? {
        val summary = PremiumSummary(
            high = hash["high"]?.toBigDecimalOrNull() ?: return corruptSummary(key),
            low = hash["low"]?.toBigDecimalOrNull() ?: return corruptSummary(key),
            current = hash["current"]?.toBigDecimalOrNull() ?: return corruptSummary(key),
            currentTimestamp = hash["current_ts"]?.toLongOrNull()?.let(Instant::ofEpochMilli)
                ?: return corruptSummary(key),
            updatedAt = hash["updated_at"]?.toLongOrNull()?.let(Instant::ofEpochMilli)
                ?: return corruptSummary(key),
        )
        metrics.record(SUMMARY_CACHE_NAME, success)
        return summary
    }

    private fun corruptSummary(key: String): PremiumSummary? {
        log.warn("Corrupt premium summary cache payload: {}", key)
        metrics.record(SUMMARY_CACHE_NAME, CacheReadOutcome.CORRUPT)
        return null
    }

    /**
     * 초당 데이터로부터 서머리 계산
     */
    fun calculateSummaryFromSeconds(symbol: String, from: Instant, to: Instant): PremiumSummary? {
        val data = getSecondsData(symbol, from, to)
        if (data.isEmpty()) return null

        val rates = data.map { it.second }
        val (currentTs, current) = data.last()

        return PremiumSummary(
            high = rates.maxOf { it },
            low = rates.minOf { it },
            current = current,
            currentTimestamp = currentTs,
            updatedAt = clock.instant(),
        )
    }

    /**
     * 집계 데이터로부터 서머리 계산 (통합)
     */
    fun calculateSummary(
        timeUnit: AggregationTimeUnit,
        symbol: String,
        from: Instant,
        to: Instant,
    ): PremiumSummary? {
        val data = getAggregationData(timeUnit, symbol, from, to)
        if (data.isEmpty()) return null

        val (_, lastAgg) = data.last()

        return PremiumSummary(
            high = data.maxOf { it.second.high },
            low = data.minOf { it.second.low },
            current = lastAgg.close,
            currentTimestamp = data.last().first,
            updatedAt = clock.instant(),
        )
    }

    // ========== 집계 유틸리티 ==========

    /**
     * 초당 데이터를 집계
     */
    fun aggregateSecondsData(symbol: String, from: Instant, to: Instant): PremiumAggregation? {
        val data = getSecondsDataFull(symbol, from, to)
        if (data.isEmpty()) return null

        val rates = data.map { it.rate }

        return PremiumAggregation(
            symbol = symbol,
            high = rates.maxOf { it },
            low = rates.minOf { it },
            open = rates.first(),
            close = rates.last(),
            avg = rates.fold(BigDecimal.ZERO) { acc, v -> acc + v }
                .divide(rates.size.toBigDecimal(), 4, RoundingMode.HALF_UP),
            count = rates.size,
            fxRate = data.last().fxRate,
        )
    }

    /**
     * 집계 데이터를 재집계 (통합)
     */
    fun aggregateData(
        timeUnit: AggregationTimeUnit,
        symbol: String,
        from: Instant,
        to: Instant,
    ): PremiumAggregation? {
        val data = getAggregationData(timeUnit, symbol, from, to)
        if (data.isEmpty()) return null

        val aggs = data.map { it.second }
        val totalCount = aggs.sumOf { it.count }

        return PremiumAggregation(
            symbol = symbol,
            high = aggs.maxOf { it.high },
            low = aggs.minOf { it.low },
            open = aggs.first().open,
            close = aggs.last().close,
            avg = aggs.fold(BigDecimal.ZERO) { acc, a -> acc + a.avg * a.count.toBigDecimal() }
                .divide(totalCount.toBigDecimal(), 4, RoundingMode.HALF_UP),
            count = totalCount,
            fxRate = aggs.last().fxRate,
        )
    }

    private companion object {
        const val SECONDS_CACHE_NAME = "premium_seconds"
        const val SUMMARY_CACHE_NAME = "premium_summary"
    }

}
