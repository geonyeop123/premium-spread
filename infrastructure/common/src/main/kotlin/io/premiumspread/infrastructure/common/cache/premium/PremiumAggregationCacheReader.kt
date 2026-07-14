package io.premiumspread.infrastructure.common.cache.premium

import io.premiumspread.domain.market.MarketPair
import io.premiumspread.domain.premium.PremiumAggregationSnapshot
import io.premiumspread.infrastructure.common.cache.CacheReadMetrics
import io.premiumspread.infrastructure.common.cache.CacheReadOutcome
import io.premiumspread.infrastructure.common.cache.shortenTtl
import io.premiumspread.redis.AggregationTimeUnit
import io.premiumspread.redis.RedisKeyGenerator
import io.premiumspread.redis.RedisTtl
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ZSetOperations
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class PremiumAggregationCacheReader(
    private val redisTemplate: StringRedisTemplate,
    private val metrics: CacheReadMetrics,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun findByInterval(
        pair: MarketPair,
        interval: String,
        from: Instant,
        to: Instant,
    ): List<PremiumAggregationSnapshot>? {
        require(from < to) { "Aggregation range must satisfy from < to." }
        return try {
            findByIntervalSafely(pair, interval, from, to)
        } catch (exception: DataAccessException) {
            log.warn("Premium aggregation cache read failed: {} {}", pair, interval, exception)
            metrics.record(CACHE_NAME, CacheReadOutcome.ERROR)
            null
        }
    }

    private fun findByIntervalSafely(
        pair: MarketPair,
        interval: String,
        from: Instant,
        to: Instant,
    ): List<PremiumAggregationSnapshot>? {
        val timeUnit = interval.toTimeUnit() ?: return null
        val v2Key = RedisKeyGenerator.premiumV2AggregationKey(
            pair.koreaExchange.name,
            pair.foreignExchange.name,
            pair.symbol.code,
            timeUnit.name,
        )
        val v2Entries = readEntries(v2Key, from, to)
        if (!v2Entries.isNullOrEmpty()) return parseAndRecord(v2Key, pair, v2Entries, CacheReadOutcome.HIT)

        metrics.record(CACHE_NAME, CacheReadOutcome.MISS)
        if (pair != MarketPair.default(pair.symbol)) return null
        val legacyKey = timeUnit.keyFor(pair.symbol.code)
        val legacyEntries = readEntries(legacyKey, from, to)
        if (legacyEntries.isNullOrEmpty()) return null
        redisTemplate.shortenTtl(legacyKey, RedisTtl.PREMIUM_LEGACY_READ_WINDOW)
        return parseAndRecord(legacyKey, pair, legacyEntries, CacheReadOutcome.LEGACY_HIT)
    }

    private fun readEntries(key: String, from: Instant, to: Instant) =
        redisTemplate.opsForZSet().rangeByScoreWithScores(
            key,
            from.toEpochMilli().toDouble(),
            Math.nextDown(to.toEpochMilli().toDouble()),
        )

    private fun parseAndRecord(
        key: String,
        pair: MarketPair,
        entries: Set<ZSetOperations.TypedTuple<String>>,
        success: CacheReadOutcome,
    ): List<PremiumAggregationSnapshot>? {
        val snapshots = entries.map { entry ->
            val parts = entry.value?.split(":") ?: return corrupt(key)
            if (parts.size < 6) return corrupt(key)
            val timestamp = entry.score?.toLong()?.let(Instant::ofEpochMilli) ?: return corrupt(key)
            runCatching {
                PremiumAggregationSnapshot(
                    pair = pair,
                    high = parts[0].toBigDecimal(),
                    low = parts[1].toBigDecimal(),
                    open = parts[2].toBigDecimal(),
                    close = parts[3].toBigDecimal(),
                    avg = parts[4].toBigDecimal(),
                    count = parts[5].toInt(),
                    observedAt = timestamp,
                    fxRate = parts.getOrNull(6)?.takeIf { it.isNotBlank() }?.toBigDecimal(),
                )
            }.getOrElse { return corrupt(key) }
        }
        metrics.record(CACHE_NAME, success)
        return snapshots
    }

    private fun corrupt(key: String): List<PremiumAggregationSnapshot>? {
        log.warn("Corrupt premium aggregation cache entry: {}", key)
        metrics.record(CACHE_NAME, CacheReadOutcome.CORRUPT)
        return null
    }

    private fun String.toTimeUnit(): AggregationTimeUnit? = when (this) {
        "1m" -> AggregationTimeUnit.MINUTES
        "1h" -> AggregationTimeUnit.HOURS
        "1d" -> AggregationTimeUnit.DAYS
        else -> null
    }

    private companion object {
        const val CACHE_NAME = "premium_aggregation"
    }
}
