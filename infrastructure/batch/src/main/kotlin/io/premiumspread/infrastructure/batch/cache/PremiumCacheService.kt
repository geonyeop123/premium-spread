package io.premiumspread.infrastructure.batch.cache

import io.premiumspread.domain.market.MarketPair
import io.premiumspread.domain.premium.PremiumRealtimeWritePort
import io.premiumspread.domain.premium.PremiumSnapshot
import io.premiumspread.domain.ticker.Exchange
import io.premiumspread.domain.ticker.Symbol
import io.premiumspread.infrastructure.common.cache.premium.PremiumCacheReader
import io.premiumspread.infrastructure.common.cache.premium.PremiumCacheWriter
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.Instant

data class PremiumCacheData(
    val premiumRate: BigDecimal,
    val koreaPrice: BigDecimal,
    val foreignPrice: BigDecimal,
    val foreignPriceInKrw: BigDecimal,
    val fxRate: BigDecimal,
    val observedAt: Instant,
    val pair: MarketPair,
    val fxSource: Exchange = Exchange.FX_PROVIDER,
    val fxObservedAt: Instant = observedAt,
) {
    val symbol: String
        get() = pair.symbol.code

    companion object {
        fun from(snapshot: PremiumSnapshot): PremiumCacheData = PremiumCacheData(
            premiumRate = snapshot.premiumRate,
            koreaPrice = snapshot.koreaPrice,
            foreignPrice = snapshot.foreignPrice,
            foreignPriceInKrw = snapshot.foreignPriceInKrw,
            fxRate = snapshot.fxRate,
            observedAt = snapshot.observedAt,
            pair = snapshot.pair,
            fxSource = snapshot.fxSource,
            fxObservedAt = snapshot.fxObservedAt,
        )
    }
}

@Service
class PremiumCacheService(
    private val cacheReader: PremiumCacheReader,
    private val cacheWriter: PremiumCacheWriter,
    private val secondsCache: PremiumSecondsCacheOperations,
    aggregationCache: PremiumAggregationCacheOperations,
    summaryCache: PremiumSummaryCacheOperations,
) : PremiumRealtimeWritePort,
    PremiumSecondsCacheOperations by secondsCache,
    PremiumAggregationCacheOperations by aggregationCache,
    PremiumSummaryCacheOperations by summaryCache {
    data class SecondsEntry(val timestamp: Instant, val rate: BigDecimal, val fxRate: BigDecimal?)

    data class PremiumSummary(
        val high: BigDecimal,
        val low: BigDecimal,
        val current: BigDecimal,
        val currentTimestamp: Instant,
        val updatedAt: Instant,
    )

    fun save(snapshot: PremiumSnapshot) {
        cacheWriter.save(snapshot)
    }

    override fun saveCurrent(snapshot: PremiumSnapshot) = save(snapshot)

    override fun saveHistory(snapshot: PremiumSnapshot) {
        cacheWriter.saveHistory(snapshot)
    }

    fun get(symbol: String): PremiumCacheData? = get(MarketPair.default(Symbol(symbol)))

    fun get(pair: MarketPair): PremiumCacheData? {
        val cached = cacheReader.get(pair) ?: return null
        return PremiumCacheData(
            premiumRate = cached.premiumRate,
            koreaPrice = cached.koreaPrice,
            foreignPrice = cached.foreignPrice,
            foreignPriceInKrw = cached.foreignPriceInKrw,
            fxRate = cached.fxRate,
            observedAt = cached.observedAt,
            pair = cached.pair,
            fxSource = cached.fxSource,
            fxObservedAt = cached.fxObservedAt,
        )
    }

    override fun saveSecond(snapshot: PremiumSnapshot) = secondsCache.saveToSeconds(snapshot)
}
