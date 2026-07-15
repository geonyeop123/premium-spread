package io.premiumspread.infrastructure.common.cache.ticker

import io.premiumspread.domain.ticker.TickerSnapshot
import io.premiumspread.infrastructure.common.cache.CacheReadMetrics
import io.premiumspread.infrastructure.common.cache.CacheReadOutcome
import io.premiumspread.infrastructure.common.cache.hasSupportedVersion
import io.premiumspread.redis.RedisKeyGenerator
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Instant

data class CachedTicker(
    val exchange: String,
    val symbol: String,
    val currency: String,
    val price: BigDecimal,
    val volume: BigDecimal?,
    val timestamp: Instant,
) {
    fun toDomain(): TickerSnapshot = TickerSnapshot(exchange, symbol, currency, price, volume, timestamp)
}

@Component
class TickerCacheReader(private val redisTemplate: StringRedisTemplate, private val metrics: CacheReadMetrics) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun get(exchange: String, symbol: String): CachedTicker? {
        val key = RedisKeyGenerator.tickerKey(exchange.lowercase(), symbol.lowercase())
        val hash = try {
            redisTemplate.opsForHash<String, String>().entries(key)
        } catch (exception: DataAccessException) {
            log.warn("Ticker cache read failed: {}", key, exception)
            metrics.record(CACHE_NAME, CacheReadOutcome.ERROR)
            return null
        }
        if (hash.isEmpty()) return miss()

        val payload = runCatching { parse(hash, exchange, symbol) }
            .onFailure { log.warn("Corrupt ticker cache payload: {}", key, it) }
            .getOrNull()
        metrics.record(CACHE_NAME, if (payload == null) CacheReadOutcome.CORRUPT else CacheReadOutcome.HIT)
        return payload
    }

    fun getSnapshot(exchange: String, symbol: String): TickerSnapshot? = get(exchange, symbol)?.toDomain()

    fun getBithumbBtc(): CachedTicker? = get("bithumb", "btc")

    fun getBinanceBtc(): CachedTicker? = get("binance", "btc")

    fun exists(exchange: String, symbol: String): Boolean = try {
        redisTemplate.hasKey(RedisKeyGenerator.tickerKey(exchange.lowercase(), symbol.lowercase()))
    } catch (exception: DataAccessException) {
        log.warn("Ticker cache exists check failed", exception)
        metrics.record(CACHE_NAME, CacheReadOutcome.ERROR)
        false
    }

    private fun parse(hash: Map<String, String>, exchange: String, symbol: String): CachedTicker? {
        if (!hash.hasSupportedVersion(allowUnversionedLegacy = true)) return null
        val storedExchange = hash["exchange"] ?: return null
        val storedSymbol = hash["symbol"] ?: return null
        if (!storedExchange.equals(exchange, ignoreCase = true) || !storedSymbol.equals(symbol, ignoreCase = true)) {
            return null
        }
        val volume = hash["volume"]?.takeIf { it.isNotBlank() }?.let { it.toBigDecimalOrNull() ?: return null }
        return CachedTicker(
            exchange = storedExchange,
            symbol = storedSymbol,
            currency = hash["currency"] ?: return null,
            price = hash["price"]?.toBigDecimalOrNull() ?: return null,
            volume = volume,
            timestamp = hash["timestamp"]?.toLongOrNull()?.let(Instant::ofEpochMilli) ?: return null,
        )
    }

    private fun miss(): CachedTicker? {
        metrics.record(CACHE_NAME, CacheReadOutcome.MISS)
        return null
    }

    private companion object {
        const val CACHE_NAME = "ticker"
    }
}
