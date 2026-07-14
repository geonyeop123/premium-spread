package io.premiumspread.infrastructure.common.cache.premium

import io.premiumspread.domain.market.MarketPair
import io.premiumspread.domain.premium.PremiumSnapshot
import io.premiumspread.domain.ticker.Exchange
import io.premiumspread.domain.ticker.Symbol
import io.premiumspread.infrastructure.common.cache.BUSINESS_PAYLOAD_VERSION
import io.premiumspread.infrastructure.common.cache.CacheReadMetrics
import io.premiumspread.infrastructure.common.cache.CacheReadOutcome
import io.premiumspread.infrastructure.common.cache.hasSupportedVersion
import io.premiumspread.infrastructure.common.cache.shortenTtl
import io.premiumspread.redis.RedisKeyGenerator
import io.premiumspread.redis.RedisTtl
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Instant

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
) {
    fun toDomain(): PremiumSnapshot = PremiumSnapshot(
        pair = pair,
        premiumRate = premiumRate,
        koreaPrice = koreaPrice,
        foreignPrice = foreignPrice,
        foreignPriceInKrw = foreignPriceInKrw,
        fxRate = fxRate,
        observedAt = observedAt,
        fxSource = fxSource,
        fxObservedAt = fxObservedAt,
    )
}

data class PremiumHistoryEntry(
    val premiumRate: BigDecimal,
    val koreaPrice: BigDecimal,
    val foreignPrice: BigDecimal,
    val timestamp: Instant,
)

@Component
class PremiumCacheReader(
    private val redisTemplate: StringRedisTemplate,
    private val metrics: CacheReadMetrics,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** v2를 우선 조회하며, default pair에 한해서만 symbol-only legacy key를 fallback한다. */
    fun get(pair: MarketPair): CachedPremium? {
        val v2Key = pair.v2Key()
        val v2Hash = try {
            redisTemplate.opsForHash<String, String>().entries(v2Key)
        } catch (exception: DataAccessException) {
            return readError(v2Key, exception)
        }
        if (v2Hash.isNotEmpty()) {
            val payload = parseSafely(v2Hash, pair, allowUnversionedLegacy = false, v2Key)
            metrics.record(CACHE_NAME, if (payload == null) CacheReadOutcome.CORRUPT else CacheReadOutcome.HIT)
            return payload
        }

        metrics.record(CACHE_NAME, CacheReadOutcome.MISS)
        if (pair != MarketPair.default(pair.symbol)) return null

        val legacyKey = RedisKeyGenerator.premiumKey(pair.symbol.code)
        val legacyHash = try {
            redisTemplate.opsForHash<String, String>().entries(legacyKey)
        } catch (exception: DataAccessException) {
            return readError(legacyKey, exception)
        }
        if (legacyHash.isEmpty()) return null
        redisTemplate.shortenTtl(legacyKey, RedisTtl.PREMIUM_LEGACY_READ_WINDOW)
        val payload = parseSafely(legacyHash, pair, allowUnversionedLegacy = true, legacyKey)
        metrics.record(CACHE_NAME, if (payload == null) CacheReadOutcome.CORRUPT else CacheReadOutcome.LEGACY_HIT)
        return payload
    }

    fun get(symbol: String): CachedPremium? = get(MarketPair.default(Symbol(symbol)))

    fun getBtc(): CachedPremium? = get("btc")

    fun getHistory(pair: MarketPair, limit: Long = 100): List<PremiumHistoryEntry> {
        val v2Key = RedisKeyGenerator.premiumV2HistoryKey(
            pair.koreaExchange.name,
            pair.foreignExchange.name,
            pair.symbol.code,
        )
        val v2Entries = try {
            redisTemplate.opsForZSet().reverseRangeWithScores(v2Key, 0, limit - 1)
        } catch (exception: DataAccessException) {
            readHistoryError(v2Key, exception)
            return emptyList()
        }
        val (selectedKey, selected, outcome) = if (!v2Entries.isNullOrEmpty()) {
            Triple(v2Key, v2Entries, CacheReadOutcome.HIT)
        } else if (pair == MarketPair.default(pair.symbol)) {
            metrics.record(HISTORY_CACHE_NAME, CacheReadOutcome.MISS)
            val legacyKey = RedisKeyGenerator.premiumHistoryKey(pair.symbol.code)
            val legacyEntries = try {
                redisTemplate.opsForZSet().reverseRangeWithScores(legacyKey, 0, limit - 1)
            } catch (exception: DataAccessException) {
                readHistoryError(legacyKey, exception)
                return emptyList()
            }
            if (legacyEntries.isNullOrEmpty()) {
                null
            } else {
                redisTemplate.shortenTtl(legacyKey, RedisTtl.PREMIUM_LEGACY_READ_WINDOW)
                Triple(legacyKey, legacyEntries, CacheReadOutcome.LEGACY_HIT)
            }
        } else {
            null
        } ?: return emptyList()

        val parsed = selected.map { entry ->
            val parts = entry.value?.split(":") ?: return corruptHistory(selectedKey)
            if (parts.size < 3) return corruptHistory(selectedKey)
            PremiumHistoryEntry(
                premiumRate = parts[0].toBigDecimalOrNull() ?: return corruptHistory(selectedKey),
                koreaPrice = parts[1].toBigDecimalOrNull() ?: return corruptHistory(selectedKey),
                foreignPrice = parts[2].toBigDecimalOrNull() ?: return corruptHistory(selectedKey),
                timestamp = entry.score?.toLong()?.let(Instant::ofEpochMilli) ?: return corruptHistory(selectedKey),
            )
        }
        metrics.record(HISTORY_CACHE_NAME, outcome)
        return parsed
    }

    fun getHistory(symbol: String, limit: Long = 100): List<PremiumHistoryEntry> =
        getHistory(MarketPair.default(Symbol(symbol)), limit)

    fun getBtcHistory(limit: Long = 100): List<PremiumHistoryEntry> = getHistory("btc", limit)

    fun exists(pair: MarketPair): Boolean = try {
        redisTemplate.hasKey(pair.v2Key()) ||
            (pair == MarketPair.default(pair.symbol) && redisTemplate.hasKey(RedisKeyGenerator.premiumKey(pair.symbol.code)))
    } catch (exception: DataAccessException) {
        log.warn("Premium cache exists check failed: {}", pair, exception)
        metrics.record(CACHE_NAME, CacheReadOutcome.ERROR)
        false
    }

    fun exists(symbol: String): Boolean = exists(MarketPair.default(Symbol(symbol)))

    private fun parseSafely(
        hash: Map<String, String>,
        requestedPair: MarketPair,
        allowUnversionedLegacy: Boolean,
        key: String,
    ): CachedPremium? = runCatching { parse(hash, requestedPair, allowUnversionedLegacy) }
        .onFailure { log.warn("Corrupt premium cache payload: {}", key, it) }
        .getOrNull()

    private fun parse(
        hash: Map<String, String>,
        requestedPair: MarketPair,
        allowUnversionedLegacy: Boolean,
    ): CachedPremium? {
        if (!hash.hasSupportedVersion(allowUnversionedLegacy)) return null
        if (!allowUnversionedLegacy && hash["schema_version"] != BUSINESS_PAYLOAD_VERSION) return null
        val symbol = hash["symbol"] ?: return null
        val observedAt = hash["observed_at"]?.toLongOrNull()?.let(Instant::ofEpochMilli) ?: return null
        val metadataKeys = setOf("korea_exchange", "foreign_exchange", "fx_source", "fx_observed_at")
        val presentMetadata = metadataKeys.filter(hash::containsKey)
        if (presentMetadata.isNotEmpty() && presentMetadata.size != metadataKeys.size) return null

        val pair = if (presentMetadata.isEmpty()) {
            MarketPair.default(Symbol(symbol))
        } else {
            MarketPair(
                symbol = Symbol(symbol),
                koreaExchange = Exchange.valueOf(hash.getValue("korea_exchange")),
                foreignExchange = Exchange.valueOf(hash.getValue("foreign_exchange")),
            )
        }
        if (pair != requestedPair) return null

        return CachedPremium(
            symbol = symbol,
            premiumRate = hash["rate"]?.toBigDecimalOrNull() ?: return null,
            koreaPrice = hash["korea_price"]?.toBigDecimalOrNull() ?: return null,
            foreignPrice = hash["foreign_price"]?.toBigDecimalOrNull() ?: return null,
            foreignPriceInKrw = hash["foreign_price_krw"]?.toBigDecimalOrNull() ?: return null,
            fxRate = hash["fx_rate"]?.toBigDecimalOrNull() ?: return null,
            observedAt = observedAt,
            pair = pair,
            fxSource = if (presentMetadata.isEmpty()) Exchange.FX_PROVIDER else Exchange.valueOf(hash.getValue("fx_source")),
            fxObservedAt = if (presentMetadata.isEmpty()) {
                observedAt
            } else {
                hash.getValue("fx_observed_at").toLongOrNull()?.let(Instant::ofEpochMilli) ?: return null
            },
        )
    }

    private fun MarketPair.v2Key(): String = RedisKeyGenerator.premiumV2Key(
        koreaExchange.name,
        foreignExchange.name,
        symbol.code,
    )

    private fun readError(key: String, exception: DataAccessException): CachedPremium? {
        log.warn("Premium cache read failed: {}", key, exception)
        metrics.record(CACHE_NAME, CacheReadOutcome.ERROR)
        return null
    }

    private fun readHistoryError(key: String, exception: DataAccessException) {
        log.warn("Premium history cache read failed: {}", key, exception)
        metrics.record(HISTORY_CACHE_NAME, CacheReadOutcome.ERROR)
    }

    private fun corruptHistory(key: String): List<PremiumHistoryEntry> {
        log.warn("Corrupt premium history cache entry: {}", key)
        metrics.record(HISTORY_CACHE_NAME, CacheReadOutcome.CORRUPT)
        return emptyList()
    }

    private companion object {
        const val CACHE_NAME = "premium"
        const val HISTORY_CACHE_NAME = "premium_history"
    }
}
