package io.premiumspread.cache

import io.premiumspread.redis.RedisKeyGenerator
import io.premiumspread.redis.RedisTtl
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import java.math.BigDecimal
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
}
