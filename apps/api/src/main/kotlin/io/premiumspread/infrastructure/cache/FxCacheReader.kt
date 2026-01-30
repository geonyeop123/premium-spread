package io.premiumspread.infrastructure.cache

import io.premiumspread.redis.RedisKeyGenerator
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Instant

/**
 * 환율 캐시 데이터
 */
data class CachedFxRate(
    val baseCurrency: String,
    val quoteCurrency: String,
    val rate: BigDecimal,
    val timestamp: Instant,
)

/**
 * 환율 캐시 Reader (API 서버용)
 *
 * 배치 서버가 저장한 환율 데이터를 읽음
 */
@Component
class FxCacheReader(
    private val redisTemplate: StringRedisTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 환율 조회
     */
    fun get(baseCurrency: String, quoteCurrency: String): CachedFxRate? {
        val key = RedisKeyGenerator.fxKey(
            base = baseCurrency.lowercase(),
            quote = quoteCurrency.lowercase(),
        )

        val hash = redisTemplate.opsForHash<String, String>().entries(key)
        if (hash.isEmpty()) {
            log.debug("FX cache miss: {}", key)
            return null
        }

        return try {
            CachedFxRate(
                baseCurrency = hash["base"] ?: return null,
                quoteCurrency = hash["quote"] ?: return null,
                rate = hash["rate"]?.toBigDecimalOrNull() ?: return null,
                timestamp = hash["timestamp"]?.toLongOrNull()
                    ?.let { Instant.ofEpochMilli(it) }
                    ?: Instant.now(),
            ).also {
                log.debug("FX cache hit: {} = {}", key, it.rate)
            }
        } catch (e: Exception) {
            log.warn("Failed to parse FX rate from cache: {}", key, e)
            null
        }
    }

    /**
     * USD/KRW 환율 조회
     */
    fun getUsdKrw(): CachedFxRate? = get("usd", "krw")

    /**
     * USD/KRW 환율 값만 조회
     */
    fun getUsdKrwRate(): BigDecimal? = getUsdKrw()?.rate

    /**
     * 환율 존재 여부 확인
     */
    fun exists(baseCurrency: String, quoteCurrency: String): Boolean {
        val key = RedisKeyGenerator.fxKey(
            base = baseCurrency.lowercase(),
            quote = quoteCurrency.lowercase(),
        )
        return redisTemplate.hasKey(key)
    }
}
