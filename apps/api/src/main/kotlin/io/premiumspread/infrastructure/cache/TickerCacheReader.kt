package io.premiumspread.infrastructure.cache

import io.premiumspread.redis.RedisKeyGenerator
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Instant

/**
 * 티커 캐시 데이터
 */
data class CachedTicker(
    val exchange: String,
    val symbol: String,
    val currency: String,
    val price: BigDecimal,
    val volume: BigDecimal?,
    val timestamp: Instant,
)

/**
 * 티커 캐시 Reader (API 서버용)
 *
 * 배치 서버가 저장한 티커 데이터를 읽음
 */
@Component
class TickerCacheReader(
    private val redisTemplate: StringRedisTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 티커 조회
     */
    fun get(exchange: String, symbol: String): CachedTicker? {
        val key = RedisKeyGenerator.tickerKey(
            exchange = exchange.lowercase(),
            symbol = symbol.lowercase(),
        )

        val hash = redisTemplate.opsForHash<String, String>().entries(key)
        if (hash.isEmpty()) {
            log.debug("Ticker cache miss: {}", key)
            return null
        }

        return try {
            CachedTicker(
                exchange = hash["exchange"] ?: return null,
                symbol = hash["symbol"] ?: return null,
                currency = hash["currency"] ?: return null,
                price = hash["price"]?.toBigDecimalOrNull() ?: return null,
                volume = hash["volume"]?.takeIf { it.isNotBlank() }?.toBigDecimalOrNull(),
                timestamp = hash["timestamp"]?.toLongOrNull()
                    ?.let { Instant.ofEpochMilli(it) }
                    ?: Instant.now(),
            ).also {
                log.debug("Ticker cache hit: {} = {}", key, it.price)
            }
        } catch (e: Exception) {
            log.warn("Failed to parse ticker from cache: {}", key, e)
            null
        }
    }

    /**
     * Bithumb BTC 티커 조회
     */
    fun getBithumbBtc(): CachedTicker? = get("bithumb", "btc")

    /**
     * Binance BTC 티커 조회
     */
    fun getBinanceBtc(): CachedTicker? = get("binance", "btc")

    /**
     * 티커 존재 여부 확인
     */
    fun exists(exchange: String, symbol: String): Boolean {
        val key = RedisKeyGenerator.tickerKey(
            exchange = exchange.lowercase(),
            symbol = symbol.lowercase(),
        )
        return redisTemplate.hasKey(key)
    }
}
