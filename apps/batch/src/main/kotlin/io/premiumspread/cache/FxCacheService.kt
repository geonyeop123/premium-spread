package io.premiumspread.cache

import io.premiumspread.client.FxRateData
import io.premiumspread.redis.RedisKeyGenerator
import io.premiumspread.redis.RedisTtl
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.Instant

@Service
class FxCacheService(
    private val redisTemplate: StringRedisTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 환율 데이터 저장
     */
    fun save(fxRate: FxRateData) {
        val key = RedisKeyGenerator.fxKey(
            base = fxRate.baseCurrency.lowercase(),
            quote = fxRate.quoteCurrency.lowercase(),
        )

        val hash = mapOf(
            "base" to fxRate.baseCurrency,
            "quote" to fxRate.quoteCurrency,
            "rate" to fxRate.rate.toPlainString(),
            "timestamp" to fxRate.timestamp.toEpochMilli().toString(),
        )

        redisTemplate.opsForHash<String, String>().putAll(key, hash)
        redisTemplate.expire(key, RedisTtl.FX)

        log.debug("Saved FX rate to cache: {} = {}", key, fxRate.rate)
    }

    /**
     * 환율 데이터 조회
     */
    fun get(baseCurrency: String, quoteCurrency: String): FxRateData? {
        val key = RedisKeyGenerator.fxKey(
            base = baseCurrency.lowercase(),
            quote = quoteCurrency.lowercase(),
        )

        val hash = redisTemplate.opsForHash<String, String>().entries(key)
        if (hash.isEmpty()) {
            return null
        }

        return try {
            FxRateData(
                baseCurrency = hash["base"] ?: return null,
                quoteCurrency = hash["quote"] ?: return null,
                rate = hash["rate"]?.toBigDecimalOrNull() ?: return null,
                timestamp = hash["timestamp"]?.toLongOrNull()
                    ?.let { Instant.ofEpochMilli(it) }
                    ?: Instant.now(),
            )
        } catch (e: Exception) {
            log.warn("Failed to parse FX rate from cache: {}", key, e)
            null
        }
    }

    /**
     * USD/KRW 환율 조회
     */
    fun getUsdKrw(): BigDecimal? {
        return get("usd", "krw")?.rate
    }
}
