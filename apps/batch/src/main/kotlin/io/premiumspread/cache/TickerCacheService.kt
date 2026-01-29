package io.premiumspread.cache

import com.fasterxml.jackson.databind.ObjectMapper
import io.premiumspread.client.TickerData
import io.premiumspread.redis.RedisKeyGenerator
import io.premiumspread.redis.RedisTtl
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service

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
}
