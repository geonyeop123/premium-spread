package io.premiumspread.infrastructure.premium

import io.premiumspread.domain.market.MarketPair
import io.premiumspread.domain.ticker.Exchange
import io.premiumspread.domain.ticker.Symbol
import io.premiumspread.redis.RedisKeyGenerator
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Instant

/**
 * 프리미엄 캐시 데이터
 */
data class CachedPremium(
    val symbol: String,
    val premiumRate: BigDecimal,
    val koreaPrice: BigDecimal,
    val foreignPrice: BigDecimal,
    val foreignPriceInKrw: BigDecimal,
    val fxRate: BigDecimal,
    val observedAt: Instant,
    val pair: MarketPair = MarketPair.default(Symbol(symbol)),
    val fxSource: Exchange = Exchange.FX_PROVIDER,
    val fxObservedAt: Instant = observedAt,
)

/**
 * 프리미엄 히스토리 데이터
 */
data class PremiumHistoryEntry(
    val premiumRate: BigDecimal,
    val koreaPrice: BigDecimal,
    val foreignPrice: BigDecimal,
    val timestamp: Instant,
)

/**
 * 프리미엄 캐시 Reader (API 서버용)
 *
 * 배치 서버가 저장한 프리미엄 데이터를 읽음
 */
@Component
class PremiumCacheReader(
    private val redisTemplate: StringRedisTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 프리미엄 조회
     */
    fun get(symbol: String): CachedPremium? {
        val key = RedisKeyGenerator.premiumKey(symbol.lowercase())

        val hash = redisTemplate.opsForHash<String, String>().entries(key)
        if (hash.isEmpty()) {
            log.debug("Premium cache miss: {}", key)
            return null
        }

        return try {
            val cachedSymbol = hash["symbol"] ?: return null
            if (!cachedSymbol.equals(symbol, ignoreCase = true)) {
                log.warn("Premium cache identity mismatch: key={}, payload={}", key, cachedSymbol)
                return null
            }
            val observedAt = hash["observed_at"]?.toLongOrNull()
                ?.let { Instant.ofEpochMilli(it) } ?: return null
            val metadataKeys = listOf("korea_exchange", "foreign_exchange", "fx_source", "fx_observed_at")
            val metadataCount = metadataKeys.count(hash::containsKey)
            if (metadataCount != 0 && metadataCount != metadataKeys.size) return null

            val pair = if (metadataCount == 0) {
                MarketPair.default(Symbol(cachedSymbol))
            } else {
                MarketPair(
                    symbol = Symbol(cachedSymbol),
                    koreaExchange = Exchange.valueOf(hash.getValue("korea_exchange")),
                    foreignExchange = Exchange.valueOf(hash.getValue("foreign_exchange")),
                )
            }
            val fxSource = if (metadataCount == 0) {
                Exchange.FX_PROVIDER
            } else {
                Exchange.valueOf(hash.getValue("fx_source"))
            }
            val fxObservedAt = if (metadataCount == 0) {
                observedAt
            } else {
                hash.getValue("fx_observed_at").toLongOrNull()
                    ?.let { Instant.ofEpochMilli(it) } ?: return null
            }

            CachedPremium(
                symbol = cachedSymbol,
                premiumRate = hash["rate"]?.toBigDecimalOrNull() ?: return null,
                koreaPrice = hash["korea_price"]?.toBigDecimalOrNull() ?: return null,
                foreignPrice = hash["foreign_price"]?.toBigDecimalOrNull() ?: return null,
                foreignPriceInKrw = hash["foreign_price_krw"]?.toBigDecimalOrNull() ?: return null,
                fxRate = hash["fx_rate"]?.toBigDecimalOrNull() ?: return null,
                observedAt = observedAt,
                pair = pair,
                fxSource = fxSource,
                fxObservedAt = fxObservedAt,
            ).also {
                log.debug("Premium cache hit: {} = {}%", key, it.premiumRate)
            }
        } catch (e: Exception) {
            log.warn("Failed to parse premium from cache: {}", key, e)
            null
        }
    }

    /**
     * BTC 프리미엄 조회
     */
    fun getBtc(): CachedPremium? = get("btc")

    /**
     * 프리미엄 히스토리 조회 (최근 N개)
     */
    fun getHistory(symbol: String, limit: Long = 100): List<PremiumHistoryEntry> {
        val key = RedisKeyGenerator.premiumHistoryKey(symbol.lowercase())

        // Sorted Set에서 최근 데이터 조회 (score = timestamp)
        val entries = redisTemplate.opsForZSet()
            .reverseRangeWithScores(key, 0, limit - 1)
            ?: return emptyList()

        return entries.mapNotNull { entry ->
            try {
                val parts = entry.value?.split(":") ?: return@mapNotNull null
                if (parts.size < 3) return@mapNotNull null

                PremiumHistoryEntry(
                    premiumRate = parts[0].toBigDecimalOrNull() ?: return@mapNotNull null,
                    koreaPrice = parts[1].toBigDecimalOrNull() ?: return@mapNotNull null,
                    foreignPrice = parts[2].toBigDecimalOrNull() ?: return@mapNotNull null,
                    timestamp = entry.score?.toLong()?.let { Instant.ofEpochMilli(it) }
                        ?: return@mapNotNull null,
                )
            } catch (e: Exception) {
                log.warn("Failed to parse premium history entry", e)
                null
            }
        }
    }

    /**
     * BTC 프리미엄 히스토리 조회
     */
    fun getBtcHistory(limit: Long = 100): List<PremiumHistoryEntry> = getHistory("btc", limit)

    /**
     * 프리미엄 존재 여부 확인
     */
    fun exists(symbol: String): Boolean {
        val key = RedisKeyGenerator.premiumKey(symbol.lowercase())
        return redisTemplate.hasKey(key)
    }
}
