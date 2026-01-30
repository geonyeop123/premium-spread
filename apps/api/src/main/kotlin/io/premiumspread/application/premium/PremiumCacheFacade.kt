package io.premiumspread.application.premium

import io.premiumspread.domain.premium.PremiumService
import io.premiumspread.domain.ticker.Symbol
import io.premiumspread.infrastructure.cache.CachedPremium
import io.premiumspread.infrastructure.cache.PremiumCacheReader
import io.premiumspread.infrastructure.cache.PremiumHistoryEntry
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Instant

/**
 * 프리미엄 캐시 우선 조회 Facade
 *
 * 캐시에서 먼저 조회하고, 없으면 DB에서 조회
 */
@Service
class PremiumCacheFacade(
    private val premiumCacheReader: PremiumCacheReader,
    private val premiumService: PremiumService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 최신 프리미엄 조회 (캐시 우선)
     */
    fun findLatest(symbol: String): PremiumCacheResult? {
        // 1. 캐시에서 조회
        val cached = premiumCacheReader.get(symbol)
        if (cached != null) {
            log.debug("Premium found in cache: {} = {}%", symbol, cached.premiumRate)
            return PremiumCacheResult.fromCache(cached)
        }

        // 2. 캐시 미스 시 DB에서 조회
        log.debug("Premium cache miss, falling back to DB: {}", symbol)
        return findFromDb(symbol)
    }

    /**
     * BTC 프리미엄 조회
     */
    fun findBtcLatest(): PremiumCacheResult? = findLatest("btc")

    /**
     * 프리미엄 히스토리 조회 (캐시에서만)
     */
    fun findHistory(symbol: String, limit: Long = 100): List<PremiumHistoryResult> {
        return premiumCacheReader.getHistory(symbol, limit)
            .map { PremiumHistoryResult.from(it) }
    }

    /**
     * BTC 프리미엄 히스토리 조회
     */
    fun findBtcHistory(limit: Long = 100): List<PremiumHistoryResult> = findHistory("btc", limit)

    /**
     * DB에서 조회 (fallback)
     */
    @Transactional(readOnly = true)
    fun findFromDb(symbol: String): PremiumCacheResult? {
        return premiumService.findLatestBySymbol(Symbol(symbol))
            ?.let { PremiumCacheResult.fromDomain(it) }
    }

    /**
     * 캐시 존재 여부
     */
    fun isCached(symbol: String): Boolean {
        return premiumCacheReader.exists(symbol)
    }
}

/**
 * 프리미엄 캐시 조회 결과
 */
data class PremiumCacheResult(
    val symbol: String,
    val premiumRate: BigDecimal,
    val koreaPrice: BigDecimal,
    val foreignPrice: BigDecimal,
    val foreignPriceInKrw: BigDecimal,
    val fxRate: BigDecimal,
    val observedAt: Instant,
    val source: DataSource,
) {
    enum class DataSource { CACHE, DATABASE }

    companion object {
        fun fromCache(cached: CachedPremium) = PremiumCacheResult(
            symbol = cached.symbol,
            premiumRate = cached.premiumRate,
            koreaPrice = cached.koreaPrice,
            foreignPrice = cached.foreignPrice,
            foreignPriceInKrw = cached.foreignPriceInKrw,
            fxRate = cached.fxRate,
            observedAt = cached.observedAt,
            source = DataSource.CACHE,
        )

        fun fromDomain(premium: io.premiumspread.domain.premium.Premium) = PremiumCacheResult(
            symbol = premium.symbol.code,
            premiumRate = premium.premiumRate,
            // DB에서 조회 시 가격 정보는 Ticker 엔티티에 있으므로 기본값 사용
            koreaPrice = BigDecimal.ZERO,
            foreignPrice = BigDecimal.ZERO,
            foreignPriceInKrw = BigDecimal.ZERO,
            fxRate = BigDecimal.ZERO,
            observedAt = premium.observedAt,
            source = DataSource.DATABASE,
        )
    }
}

/**
 * 프리미엄 히스토리 결과
 */
data class PremiumHistoryResult(
    val premiumRate: BigDecimal,
    val koreaPrice: BigDecimal,
    val foreignPrice: BigDecimal,
    val timestamp: Instant,
) {
    companion object {
        fun from(entry: PremiumHistoryEntry) = PremiumHistoryResult(
            premiumRate = entry.premiumRate,
            koreaPrice = entry.koreaPrice,
            foreignPrice = entry.foreignPrice,
            timestamp = entry.timestamp,
        )
    }
}
