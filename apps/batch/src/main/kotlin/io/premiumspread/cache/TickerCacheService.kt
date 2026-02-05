package io.premiumspread.cache

import com.fasterxml.jackson.databind.ObjectMapper
import io.premiumspread.client.TickerData
import io.premiumspread.redis.RedisKeyGenerator
import io.premiumspread.redis.RedisTtl
import io.premiumspread.redis.TickerAggregationTimeUnit
import io.premiumspread.repository.TickerAggregation
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ZSetOperations.TypedTuple
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

@Service
class TickerCacheService(
    private val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 티커 데이터 저장
     */
    fun save(ticker: TickerData) {
        val key = RedisKeyGenerator.tickerKey(
            exchange = ticker.exchange.lowercase(),
            symbol = ticker.symbol.lowercase(),
        )

        val hash = mapOf(
            "exchange" to ticker.exchange,
            "symbol" to ticker.symbol,
            "currency" to ticker.currency,
            "price" to ticker.price.toPlainString(),
            "volume" to (ticker.volume?.toPlainString() ?: ""),
            "timestamp" to ticker.timestamp.toEpochMilli().toString(),
        )

        redisTemplate.opsForHash<String, String>().putAll(key, hash)
        redisTemplate.expire(key, RedisTtl.TICKER)

        log.debug("Saved ticker to cache: {} = {}", key, ticker.price)
    }

    /**
     * 여러 티커 데이터 저장
     */
    fun saveAll(vararg tickers: TickerData) {
        tickers.forEach { save(it) }
    }

    /**
     * 티커 데이터 조회
     */
    fun get(exchange: String, symbol: String): TickerData? {
        val key = RedisKeyGenerator.tickerKey(
            exchange = exchange.lowercase(),
            symbol = symbol.lowercase(),
        )

        val hash = redisTemplate.opsForHash<String, String>().entries(key)
        if (hash.isEmpty()) {
            return null
        }

        return try {
            TickerData(
                exchange = hash["exchange"] ?: return null,
                symbol = hash["symbol"] ?: return null,
                currency = hash["currency"] ?: return null,
                price = hash["price"]?.toBigDecimalOrNull() ?: return null,
                volume = hash["volume"]?.takeIf { it.isNotBlank() }?.toBigDecimalOrNull(),
                timestamp = hash["timestamp"]?.toLongOrNull()
                    ?.let { java.time.Instant.ofEpochMilli(it) }
                    ?: java.time.Instant.now(),
            )
        } catch (e: Exception) {
            log.warn("Failed to parse ticker from cache: {}", key, e)
            null
        }
    }

    // ========== 초당 데이터 ZSet 저장 ==========

    /**
     * 초당 데이터 ZSet에 저장
     */
    fun saveToSeconds(ticker: TickerData) {
        val key = TickerAggregationTimeUnit.SECONDS.keyFor(ticker.exchange, ticker.symbol)
        val score = ticker.timestamp.toEpochMilli().toDouble()
        val value = ticker.price.toPlainString()

        redisTemplate.opsForZSet().add(key, value, score)
        redisTemplate.expire(key, RedisTtl.SECONDS_DATA)

        // 5분 이전 데이터 삭제
        val cutoff = Instant.now().minus(RedisTtl.SECONDS_DATA).toEpochMilli().toDouble()
        redisTemplate.opsForZSet().removeRangeByScore(key, Double.NEGATIVE_INFINITY, cutoff)

        log.debug("Saved ticker to seconds ZSet: {} = {}", key, ticker.price)
    }

    /**
     * 초당 데이터 조회 (시간 범위)
     */
    fun getSecondsData(exchange: String, symbol: String, from: Instant, to: Instant): List<Pair<Instant, BigDecimal>> {
        val key = TickerAggregationTimeUnit.SECONDS.keyFor(exchange, symbol)
        val entries = redisTemplate.opsForZSet().rangeByScoreWithScores(
            key,
            from.toEpochMilli().toDouble(),
            to.toEpochMilli().toDouble(),
        ) ?: return emptyList()

        return entries.mapNotNull { entry ->
            val price = entry.value?.toBigDecimalOrNull() ?: return@mapNotNull null
            val timestamp = entry.score?.toLong()?.let { Instant.ofEpochMilli(it) } ?: return@mapNotNull null
            timestamp to price
        }
    }

    // ========== 집계 데이터 저장/조회 ==========

    /**
     * 집계 데이터 ZSet에 저장
     */
    fun saveAggregation(
        timeUnit: TickerAggregationTimeUnit,
        exchange: String,
        symbol: String,
        timestamp: Instant,
        agg: TickerAggregation,
    ) {
        val key = timeUnit.keyFor(exchange, symbol)
        val score = timestamp.toEpochMilli().toDouble()
        val value = "${agg.high}:${agg.low}:${agg.open}:${agg.close}:${agg.avg}:${agg.count}"

        redisTemplate.opsForZSet().add(key, value, score)
        redisTemplate.expire(key, timeUnit.ttl)

        // TTL 이전 데이터 삭제
        val cutoff = Instant.now().minus(timeUnit.ttl).toEpochMilli().toDouble()
        redisTemplate.opsForZSet().removeRangeByScore(key, Double.NEGATIVE_INFINITY, cutoff)

        log.debug("Saved ticker aggregation to {}: {}:{} at {}", timeUnit, exchange, symbol, timestamp)
    }

    /**
     * 집계 데이터 조회
     */
    fun getAggregationData(
        timeUnit: TickerAggregationTimeUnit,
        exchange: String,
        symbol: String,
        from: Instant,
        to: Instant,
    ): List<Pair<Instant, TickerAggregation>> {
        val key = timeUnit.keyFor(exchange, symbol)
        val entries = redisTemplate.opsForZSet().rangeByScoreWithScores(
            key,
            from.toEpochMilli().toDouble(),
            to.toEpochMilli().toDouble(),
        ) ?: return emptyList()

        return entries.mapNotNull { entry -> parseAggregation(exchange, symbol, entry) }
    }

    private fun parseAggregation(
        exchange: String,
        symbol: String,
        entry: TypedTuple<String>,
    ): Pair<Instant, TickerAggregation>? {
        val parts = entry.value?.split(":") ?: return null
        if (parts.size < 6) return null
        val timestamp = entry.score?.toLong()?.let { Instant.ofEpochMilli(it) } ?: return null
        return timestamp to TickerAggregation(
            exchange = exchange,
            symbol = symbol,
            high = parts[0].toBigDecimalOrNull() ?: return null,
            low = parts[1].toBigDecimalOrNull() ?: return null,
            open = parts[2].toBigDecimalOrNull() ?: return null,
            close = parts[3].toBigDecimalOrNull() ?: return null,
            avg = parts[4].toBigDecimalOrNull() ?: return null,
            count = parts[5].toIntOrNull() ?: return null,
        )
    }

    // ========== 집계 유틸리티 ==========

    /**
     * 초당 데이터를 집계
     */
    fun aggregateSecondsData(exchange: String, symbol: String, from: Instant, to: Instant): TickerAggregation? {
        val data = getSecondsData(exchange, symbol, from, to)
        if (data.isEmpty()) return null

        val prices = data.map { it.second }

        return TickerAggregation(
            exchange = exchange,
            symbol = symbol,
            high = prices.maxOf { it },
            low = prices.minOf { it },
            open = prices.first(),
            close = prices.last(),
            avg = prices.fold(BigDecimal.ZERO) { acc, v -> acc + v }
                .divide(prices.size.toBigDecimal(), 4, RoundingMode.HALF_UP),
            count = prices.size,
        )
    }

    /**
     * 집계 데이터를 재집계
     */
    fun aggregateData(
        timeUnit: TickerAggregationTimeUnit,
        exchange: String,
        symbol: String,
        from: Instant,
        to: Instant,
    ): TickerAggregation? {
        val data = getAggregationData(timeUnit, exchange, symbol, from, to)
        if (data.isEmpty()) return null

        val aggs = data.map { it.second }
        val totalCount = aggs.sumOf { it.count }

        return TickerAggregation(
            exchange = exchange,
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
