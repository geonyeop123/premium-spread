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
import org.springframework.data.redis.core.ZSetOperations
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

private fun MarketPair.v2Key(): String = RedisKeyGenerator.premiumV2Key(
    koreaExchange.name,
    foreignExchange.name,
    symbol.code,
)

@Component
class PremiumCacheReader(private val redisTemplate: StringRedisTemplate, private val metrics: CacheReadMetrics) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** v2를 우선 조회하며, default pair에 한해서만 symbol-only legacy key를 fallback한다. */
    fun get(pair: MarketPair): CachedPremium? {
        val selected = selectPayload(pair) ?: return null
        val payload = parseSafely(selected.value, pair, selected.allowUnversionedLegacy, selected.key)
        metrics.record(CACHE_NAME, selected.outcomeFor(payload))
        return payload
    }

    fun get(symbol: String): CachedPremium? = get(MarketPair.default(Symbol(symbol)))

    fun getBtc(): CachedPremium? = get("btc")

    fun findAllArchived(pair: MarketPair, limit: Long = 100): List<PremiumHistoryEntry> {
        val selected = selectHistory(pair, limit) ?: return emptyList()
        val parsed = selected.value.map { entry ->
            parseHistoryEntry(entry) ?: return corruptHistory(selected.key)
        }
        metrics.record(HISTORY_CACHE_NAME, selected.hitOutcome)
        return parsed
    }

    fun findAllArchived(symbol: String, limit: Long = 100): List<PremiumHistoryEntry> =
        findAllArchived(MarketPair.default(Symbol(symbol)), limit)

    fun getBtcHistory(limit: Long = 100): List<PremiumHistoryEntry> = findAllArchived("btc", limit)

    fun exists(pair: MarketPair): Boolean = try {
        redisTemplate.hasKey(pair.v2Key()) ||
            (pair == MarketPair.default(pair.symbol) && redisTemplate.hasKey(RedisKeyGenerator.premiumKey(pair.symbol.code)))
    } catch (exception: DataAccessException) {
        log.warn("Premium cache exists check failed: {}", pair, exception)
        metrics.record(CACHE_NAME, CacheReadOutcome.ERROR)
        false
    }

    fun exists(symbol: String): Boolean = exists(MarketPair.default(Symbol(symbol)))

    private fun selectPayload(pair: MarketPair): CacheSelection<Map<String, String>>? {
        val v2Key = pair.v2Key()
        return when (val read = readHash(v2Key)) {
            is RedisRead.Found -> CacheSelection(v2Key, read.value, CacheReadOutcome.HIT)
            RedisRead.Failed -> null
            RedisRead.Missing -> selectLegacyPayload(pair)
        }
    }

    private fun selectLegacyPayload(pair: MarketPair): CacheSelection<Map<String, String>>? {
        metrics.record(CACHE_NAME, CacheReadOutcome.MISS)
        if (pair != MarketPair.default(pair.symbol)) return null

        val legacyKey = RedisKeyGenerator.premiumKey(pair.symbol.code)
        return when (val read = readHash(legacyKey)) {
            is RedisRead.Found -> {
                redisTemplate.shortenTtl(legacyKey, RedisTtl.PREMIUM_LEGACY_READ_WINDOW)
                CacheSelection(legacyKey, read.value, CacheReadOutcome.LEGACY_HIT, allowUnversionedLegacy = true)
            }

            RedisRead.Failed, RedisRead.Missing -> null
        }
    }

    private fun readHash(key: String): RedisRead<Map<String, String>> = try {
        redisTemplate.opsForHash<String, String>().entries(key)
            .takeUnless(Map<String, String>::isEmpty)
            ?.let { hash -> RedisRead.Found(hash) }
            ?: RedisRead.Missing
    } catch (exception: DataAccessException) {
        recordReadError(CACHE_NAME, key, exception)
        RedisRead.Failed
    }

    private fun selectHistory(pair: MarketPair, limit: Long): CacheSelection<Set<ZSetOperations.TypedTuple<String>>>? {
        val v2Key = RedisKeyGenerator.premiumV2HistoryKey(
            pair.koreaExchange.name,
            pair.foreignExchange.name,
            pair.symbol.code,
        )
        return when (val read = readHistory(v2Key, limit)) {
            is RedisRead.Found -> CacheSelection(v2Key, read.value, CacheReadOutcome.HIT)
            RedisRead.Failed -> null
            RedisRead.Missing -> selectLegacyHistory(pair, limit)
        }
    }

    private fun selectLegacyHistory(
        pair: MarketPair,
        limit: Long,
    ): CacheSelection<Set<ZSetOperations.TypedTuple<String>>>? {
        metrics.record(HISTORY_CACHE_NAME, CacheReadOutcome.MISS)
        if (pair != MarketPair.default(pair.symbol)) return null

        val legacyKey = RedisKeyGenerator.premiumHistoryKey(pair.symbol.code)
        return when (val read = readHistory(legacyKey, limit)) {
            is RedisRead.Found -> {
                redisTemplate.shortenTtl(legacyKey, RedisTtl.PREMIUM_LEGACY_READ_WINDOW)
                CacheSelection(legacyKey, read.value, CacheReadOutcome.LEGACY_HIT)
            }

            RedisRead.Failed, RedisRead.Missing -> null
        }
    }

    private fun readHistory(key: String, limit: Long): RedisRead<Set<ZSetOperations.TypedTuple<String>>> = try {
        redisTemplate.opsForZSet().reverseRangeWithScores(key, 0, limit - 1)
            ?.takeUnless(Set<ZSetOperations.TypedTuple<String>>::isEmpty)
            ?.let { entries -> RedisRead.Found(entries) }
            ?: RedisRead.Missing
    } catch (exception: DataAccessException) {
        recordReadError(HISTORY_CACHE_NAME, key, exception)
        RedisRead.Failed
    }

    @Suppress("ReturnCount")
    private fun parseHistoryEntry(entry: ZSetOperations.TypedTuple<String>): PremiumHistoryEntry? {
        val parts = entry.value?.split(":") ?: return null
        if (parts.size < 3) return null
        return PremiumHistoryEntry(
            premiumRate = parts[0].toBigDecimalOrNull() ?: return null,
            koreaPrice = parts[1].toBigDecimalOrNull() ?: return null,
            foreignPrice = parts[2].toBigDecimalOrNull() ?: return null,
            timestamp = entry.score?.toLong()?.let(Instant::ofEpochMilli) ?: return null,
        )
    }

    private fun parseSafely(
        hash: Map<String, String>,
        requestedPair: MarketPair,
        allowUnversionedLegacy: Boolean,
        key: String,
    ): CachedPremium? = runCatching { parse(hash, requestedPair, allowUnversionedLegacy) }
        .onFailure { log.warn("Corrupt premium cache payload: {}", key, it) }
        .getOrNull()

    // Cache payload validation is intentionally fail-fast at every required field boundary.
    @Suppress("ReturnCount")
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

    private fun recordReadError(cacheName: String, key: String, exception: DataAccessException) {
        log.warn("Premium cache read failed: cache={}, key={}", cacheName, key, exception)
        metrics.record(cacheName, CacheReadOutcome.ERROR)
    }

    private fun corruptHistory(key: String): List<PremiumHistoryEntry> {
        log.warn("Corrupt premium history cache entry: {}", key)
        metrics.record(HISTORY_CACHE_NAME, CacheReadOutcome.CORRUPT)
        return emptyList()
    }

    private data class CacheSelection<T>(
        val key: String,
        val value: T,
        val hitOutcome: CacheReadOutcome,
        val allowUnversionedLegacy: Boolean = false,
    ) {
        fun outcomeFor(payload: CachedPremium?): CacheReadOutcome =
            if (payload == null) CacheReadOutcome.CORRUPT else hitOutcome
    }

    private sealed interface RedisRead<out T> {
        data class Found<T>(val value: T) : RedisRead<T>

        data object Missing : RedisRead<Nothing>

        data object Failed : RedisRead<Nothing>
    }

    private companion object {
        const val CACHE_NAME = "premium"
        const val HISTORY_CACHE_NAME = "premium_history"
    }
}
