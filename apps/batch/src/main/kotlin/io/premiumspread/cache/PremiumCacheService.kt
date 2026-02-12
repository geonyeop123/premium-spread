package io.premiumspread.cache

import io.premiumspread.redis.AggregationTimeUnit
import io.premiumspread.redis.RedisKeyGenerator
import io.premiumspread.redis.RedisTtl
import io.premiumspread.repository.PremiumAggregation
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ZSetOperations.TypedTuple
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

/**
 * 프리미엄 캐시 데이터
 */
data class PremiumCacheData(
    val symbol: String,
    val premiumRate: BigDecimal,
    val koreaPrice: BigDecimal,
    val foreignPrice: BigDecimal,
    val foreignPriceInKrw: BigDecimal,
    val fxRate: BigDecimal,
    val observedAt: Instant,
)

@Service
class PremiumCacheService(
    private val redisTemplate: StringRedisTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 프리미엄 데이터 저장
     */
    fun save(premium: PremiumCacheData) {
        val key = RedisKeyGenerator.premiumKey(premium.symbol.lowercase())

        val hash = mapOf(
            "symbol" to premium.symbol,
            "rate" to premium.premiumRate.toPlainString(),
            "korea_price" to premium.koreaPrice.toPlainString(),
            "foreign_price" to premium.foreignPrice.toPlainString(),
            "foreign_price_krw" to premium.foreignPriceInKrw.toPlainString(),
            "fx_rate" to premium.fxRate.toPlainString(),
            "observed_at" to premium.observedAt.toEpochMilli().toString(),
        )

        redisTemplate.opsForHash<String, String>().putAll(key, hash)
        redisTemplate.expire(key, RedisTtl.PREMIUM)

        log.debug("Saved premium to cache: {} = {}%", key, premium.premiumRate)
    }

    /**
     * 프리미엄 히스토리 저장 (Sorted Set)
     */
    fun saveHistory(premium: PremiumCacheData) {
        val key = RedisKeyGenerator.premiumHistoryKey(premium.symbol.lowercase())
        val score = premium.observedAt.toEpochMilli().toDouble()
        val value = "${premium.premiumRate}:${premium.koreaPrice}:${premium.foreignPrice}"

        redisTemplate.opsForZSet().add(key, value, score)
        redisTemplate.expire(key, RedisTtl.PREMIUM_HISTORY)

        // 1시간 이전 데이터 삭제
        val cutoff = Instant.now().minusSeconds(RedisTtl.PREMIUM_HISTORY.seconds).toEpochMilli().toDouble()
        redisTemplate.opsForZSet().removeRangeByScore(key, Double.NEGATIVE_INFINITY, cutoff)
    }

    /**
     * 프리미엄 데이터 조회
     */
    fun get(symbol: String): PremiumCacheData? {
        val key = RedisKeyGenerator.premiumKey(symbol.lowercase())

        val hash = redisTemplate.opsForHash<String, String>().entries(key)
        if (hash.isEmpty()) {
            return null
        }

        return try {
            PremiumCacheData(
                symbol = hash["symbol"] ?: return null,
                premiumRate = hash["rate"]?.toBigDecimalOrNull() ?: return null,
                koreaPrice = hash["korea_price"]?.toBigDecimalOrNull() ?: return null,
                foreignPrice = hash["foreign_price"]?.toBigDecimalOrNull() ?: return null,
                foreignPriceInKrw = hash["foreign_price_krw"]?.toBigDecimalOrNull() ?: return null,
                fxRate = hash["fx_rate"]?.toBigDecimalOrNull() ?: return null,
                observedAt = hash["observed_at"]?.toLongOrNull()
                    ?.let { Instant.ofEpochMilli(it) }
                    ?: Instant.now(),
            )
        } catch (e: Exception) {
            log.warn("Failed to parse premium from cache: {}", key, e)
            null
        }
    }

    // ========== 초당 데이터 ZSet 저장 ==========

    /**
     * 초당 데이터 ZSet에 저장 (DB INSERT 대체)
     */
    fun saveToSeconds(premium: PremiumCacheData) {
        val key = RedisKeyGenerator.premiumSecondsKey(premium.symbol.lowercase())
        val score = premium.observedAt.toEpochMilli().toDouble()
        val value = "${premium.premiumRate}:${premium.koreaPrice}:${premium.foreignPrice}:${premium.fxRate}"

        redisTemplate.opsForZSet().add(key, value, score)
        redisTemplate.expire(key, RedisTtl.SECONDS_DATA)

        // 5분 이전 데이터 삭제
        val cutoff = Instant.now().minus(RedisTtl.SECONDS_DATA).toEpochMilli().toDouble()
        redisTemplate.opsForZSet().removeRangeByScore(key, Double.NEGATIVE_INFINITY, cutoff)

        log.debug("Saved premium to seconds ZSet: {} = {}%", key, premium.premiumRate)
    }

    /**
     * 초당 데이터 조회 (시간 범위)
     */
    fun getSecondsData(symbol: String, from: Instant, to: Instant): List<Pair<Instant, BigDecimal>> {
        val key = RedisKeyGenerator.premiumSecondsKey(symbol.lowercase())
        val entries = redisTemplate.opsForZSet().rangeByScoreWithScores(
            key,
            from.toEpochMilli().toDouble(),
            to.toEpochMilli().toDouble(),
        ) ?: return emptyList()

        return entries.mapNotNull { entry ->
            val parts = entry.value?.split(":") ?: return@mapNotNull null
            val rate = parts.getOrNull(0)?.toBigDecimalOrNull() ?: return@mapNotNull null
            val timestamp = entry.score?.toLong()?.let { Instant.ofEpochMilli(it) } ?: return@mapNotNull null
            timestamp to rate
        }
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
    ) {
        val key = timeUnit.keyFor(symbol)
        val score = timestamp.toEpochMilli().toDouble()
        val value = "${agg.high}:${agg.low}:${agg.open}:${agg.close}:${agg.avg}:${agg.count}"

        redisTemplate.opsForZSet().add(key, value, score)
        redisTemplate.expire(key, timeUnit.ttl)

        // TTL 이전 데이터 삭제
        val cutoff = Instant.now().minus(timeUnit.ttl).toEpochMilli().toDouble()
        redisTemplate.opsForZSet().removeRangeByScore(key, Double.NEGATIVE_INFINITY, cutoff)

        log.debug("Saved aggregation to {}: {} at {}", timeUnit, symbol, timestamp)
    }

    /**
     * 집계 데이터 조회 (통합)
     */
    fun getAggregationData(
        timeUnit: AggregationTimeUnit,
        symbol: String,
        from: Instant,
        to: Instant,
    ): List<Pair<Instant, PremiumAggregation>> {
        val key = timeUnit.keyFor(symbol)
        val entries = redisTemplate.opsForZSet().rangeByScoreWithScores(
            key,
            from.toEpochMilli().toDouble(),
            to.toEpochMilli().toDouble(),
        ) ?: return emptyList()

        return entries.mapNotNull { entry -> parseAggregation(symbol, entry) }
    }

    /**
     * ZSet entry를 PremiumAggregation으로 파싱
     */
    private fun parseAggregation(
        symbol: String,
        entry: TypedTuple<String>,
    ): Pair<Instant, PremiumAggregation>? {
        val parts = entry.value?.split(":") ?: return null
        if (parts.size < 6) return null
        val timestamp = entry.score?.toLong()?.let { Instant.ofEpochMilli(it) } ?: return null
        return timestamp to PremiumAggregation(
            symbol = symbol,
            high = parts[0].toBigDecimalOrNull() ?: return null,
            low = parts[1].toBigDecimalOrNull() ?: return null,
            open = parts[2].toBigDecimalOrNull() ?: return null,
            close = parts[3].toBigDecimalOrNull() ?: return null,
            avg = parts[4].toBigDecimalOrNull() ?: return null,
            count = parts[5].toIntOrNull() ?: return null,
        )
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
        val key = RedisKeyGenerator.summaryKey(interval, symbol.lowercase())

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
        val key = RedisKeyGenerator.summaryKey(interval, symbol.lowercase())

        val hash = redisTemplate.opsForHash<String, String>().entries(key)
        if (hash.isEmpty()) return null

        return try {
            PremiumSummary(
                high = hash["high"]?.toBigDecimalOrNull() ?: return null,
                low = hash["low"]?.toBigDecimalOrNull() ?: return null,
                current = hash["current"]?.toBigDecimalOrNull() ?: return null,
                currentTimestamp = hash["current_ts"]?.toLongOrNull()
                    ?.let { Instant.ofEpochMilli(it) } ?: return null,
                updatedAt = hash["updated_at"]?.toLongOrNull()
                    ?.let { Instant.ofEpochMilli(it) } ?: Instant.now(),
            )
        } catch (e: Exception) {
            log.warn("Failed to parse summary from cache: {}", key, e)
            null
        }
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
            updatedAt = Instant.now(),
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
            updatedAt = Instant.now(),
        )
    }

    // ========== 집계 유틸리티 ==========

    /**
     * 초당 데이터를 집계
     */
    fun aggregateSecondsData(symbol: String, from: Instant, to: Instant): PremiumAggregation? {
        val data = getSecondsData(symbol, from, to)
        if (data.isEmpty()) return null

        val rates = data.map { it.second }

        return PremiumAggregation(
            symbol = symbol,
            high = rates.maxOf { it },
            low = rates.minOf { it },
            open = rates.first(),
            close = rates.last(),
            avg = rates.fold(BigDecimal.ZERO) { acc, v -> acc + v }
                .divide(rates.size.toBigDecimal(), 4, RoundingMode.HALF_UP),
            count = rates.size,
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
        )
    }
}
