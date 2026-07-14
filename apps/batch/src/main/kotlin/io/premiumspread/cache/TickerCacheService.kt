package io.premiumspread.cache

import com.fasterxml.jackson.databind.ObjectMapper
import io.premiumspread.client.TickerData
import io.premiumspread.domain.ticker.TickerSnapshot
import io.premiumspread.redis.RedisKeyGenerator
import io.premiumspread.redis.RedisTtl
import io.premiumspread.redis.TickerAggregationTimeUnit
import io.premiumspread.redis.support.TimeSeriesCacheSupport
import io.premiumspread.repository.TickerAggregation
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ZSetOperations.TypedTuple
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.Clock

@Service
class TickerCacheService(
    private val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
    private val timeSeriesCache: TimeSeriesCacheSupport,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 티커 데이터 저장
     */
    fun save(ticker: TickerData) {
        // TODO(Phase 4): MarketPair-aware Redis v2 key로 dual-write 후 legacy key를 제거한다.
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
        // TODO(Phase 4): v2 key 우선 dual-read로 전환한다. 현재 key는 API 호환을 위해 유지한다.
        val key = RedisKeyGenerator.tickerKey(
            exchange = exchange.lowercase(),
            symbol = symbol.lowercase(),
        )

        val hash = redisTemplate.opsForHash<String, String>().entries(key)
        if (hash.isEmpty()) {
            return null
        }

        return try {
            val storedExchange = hash["exchange"] ?: return null
            val storedSymbol = hash["symbol"] ?: return null
            if (!storedExchange.equals(exchange, ignoreCase = true) ||
                !storedSymbol.equals(symbol, ignoreCase = true)
            ) {
                log.warn("Ticker cache identity mismatch: key={}, exchange={}, symbol={}", key, storedExchange, storedSymbol)
                return null
            }

            TickerData(
                exchange = storedExchange,
                symbol = storedSymbol,
                currency = hash["currency"] ?: return null,
                price = hash["price"]?.toBigDecimalOrNull() ?: return null,
                volume = hash["volume"]?.takeIf { it.isNotBlank() }?.toBigDecimalOrNull(),
                timestamp = hash["timestamp"]?.toLongOrNull()
                    ?.let { Instant.ofEpochMilli(it) }
                    ?: return null,
            )
        } catch (e: Exception) {
            log.warn("Failed to parse ticker from cache: {}", key, e)
            null
        }
    }

    fun getSnapshot(exchange: String, symbol: String): TickerSnapshot? = get(exchange, symbol)?.toDomainSnapshot()

    // ========== 초당 데이터 ZSet 저장 ==========

    /**
     * 초당 데이터 ZSet에 저장 (score 명시 + 멤버에 timestamp 포함하여 유일성 보장).
     *
     * - 멤버 포맷: `{epochMs}:{price}` — 같은 가격이 연속 flush돼도 distinct entries 누적.
     * - Hash는 건드리지 않음 (Phase 3 freshness 5s TTL 의미 보존).
     */
    fun saveToSecondsWithScore(ticker: TickerData, scoreInstant: Instant) {
        val key = TickerAggregationTimeUnit.SECONDS.keyFor(ticker.exchange, ticker.symbol)
        val score = scoreInstant.toEpochMilli().toDouble()
        val member = "${scoreInstant.toEpochMilli()}:${ticker.price.toPlainString()}"

        redisTemplate.opsForZSet().add(key, member, score)
        redisTemplate.expire(key, RedisTtl.SECONDS_DATA)

        // retention: TTL 이전 데이터 정리
        val cutoff = clock.instant().minus(RedisTtl.SECONDS_DATA).toEpochMilli().toDouble()
        redisTemplate.opsForZSet().removeRangeByScore(key, Double.NEGATIVE_INFINITY, cutoff)

        log.debug("Saved ticker to seconds ZSet (member={}, score={}): {}", member, score, key)
    }

    /**
     * 초당 데이터 ZSet에 저장 (ticker.timestamp를 score로 사용 — REST 경로 기본).
     */
    fun saveToSeconds(ticker: TickerData) {
        saveToSecondsWithScore(ticker, ticker.timestamp)
    }

    /**
     * 초당 데이터 조회 (시간 범위).
     *
     * 멤버 포맷 호환:
     * - 신: `{epochMs}:{price}` → `":"` 뒤가 price
     * - 구: `{price}` 단독 → entry.value 전체가 price
     */
    fun getSecondsData(exchange: String, symbol: String, from: Instant, to: Instant): List<Pair<Instant, BigDecimal>> {
        val key = TickerAggregationTimeUnit.SECONDS.keyFor(exchange, symbol)
        val entries = timeSeriesCache.rangeByTime(key, from, to)

        return entries.mapNotNull { entry ->
            val member = entry.value ?: return@mapNotNull null
            val priceStr = if (":" in member) member.substringAfter(":") else member
            val price = priceStr.toBigDecimalOrNull() ?: return@mapNotNull null
            val timestamp = timeSeriesCache.extractTimestamp(entry) ?: return@mapNotNull null
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
        val value = "${agg.high}:${agg.low}:${agg.open}:${agg.close}:${agg.avg}:${agg.count}"

        timeSeriesCache.add(key, value, timestamp, timeUnit.ttl)

        log.debug("Saved ticker aggregation to {}: {}:{} at {}", timeUnit, exchange, symbol, timestamp)
    }

    /**
     * 집계 데이터 조회
     */
    fun getAggregationData(
        timeUnit: TickerAggregationTimeUnit,
        exchange: String,
        symbol: String,
        currency: String,
        from: Instant,
        to: Instant,
    ): List<Pair<Instant, TickerAggregation>> {
        val key = timeUnit.keyFor(exchange, symbol)
        val entries = timeSeriesCache.rangeByTime(key, from, to)

        return entries.mapNotNull { entry -> parseAggregation(exchange, symbol, currency, entry) }
    }

    private fun parseAggregation(
        exchange: String,
        symbol: String,
        currency: String,
        entry: TypedTuple<String>,
    ): Pair<Instant, TickerAggregation>? {
        val parts = entry.value?.split(":") ?: return null
        if (parts.size < 6) return null
        val timestamp = timeSeriesCache.extractTimestamp(entry) ?: return null
        return timestamp to TickerAggregation(
            exchange = exchange,
            symbol = symbol,
            currency = currency,
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
    fun aggregateSecondsData(exchange: String, symbol: String, currency: String, from: Instant, to: Instant): TickerAggregation? {
        val data = getSecondsData(exchange, symbol, from, to)
        if (data.isEmpty()) return null

        val prices = data.map { it.second }

        return TickerAggregation(
            exchange = exchange,
            symbol = symbol,
            currency = currency,
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
        currency: String,
        from: Instant,
        to: Instant,
    ): TickerAggregation? {
        val data = getAggregationData(timeUnit, exchange, symbol, currency, from, to)
        if (data.isEmpty()) return null

        val aggs = data.map { it.second }
        val totalCount = aggs.sumOf { it.count }

        return TickerAggregation(
            exchange = exchange,
            symbol = symbol,
            currency = currency,
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
