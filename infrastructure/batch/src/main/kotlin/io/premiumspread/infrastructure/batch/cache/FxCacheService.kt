package io.premiumspread.infrastructure.batch.cache

import io.premiumspread.infrastructure.batch.exchange.FxRateData
import io.premiumspread.domain.exchangerate.ExchangeRateSnapshot
import io.premiumspread.domain.market.FxRateCacheWritePort
import io.premiumspread.infrastructure.common.cache.exchangerate.FxCacheReader
import io.premiumspread.infrastructure.common.cache.exchangerate.FxCacheWriter
import org.springframework.stereotype.Service
import java.math.BigDecimal

@Service
class FxCacheService(private val cacheReader: FxCacheReader, private val cacheWriter: FxCacheWriter) : FxRateCacheWritePort {
    /**
     * 환율 데이터 저장
     */
    fun save(fxRate: FxRateData) {
        cacheWriter.save(fxRate.toDomainSnapshot())
    }

    override fun save(snapshot: ExchangeRateSnapshot) {
        cacheWriter.save(snapshot)
    }

    /**
     * 환율 데이터 조회
     */
    fun get(baseCurrency: String, quoteCurrency: String): FxRateData? {
        val cached = cacheReader.get(baseCurrency, quoteCurrency) ?: return null
        return FxRateData(cached.baseCurrency, cached.quoteCurrency, cached.rate, cached.timestamp, cached.source)
    }

    /**
     * USD/KRW 환율 조회
     */
    fun getUsdKrw(): BigDecimal? = get("usd", "krw")?.rate

    fun getUsdKrwData(): FxRateData? = get("usd", "krw")

    fun getUsdKrwSnapshot(): ExchangeRateSnapshot? = get("usd", "krw")?.toDomainSnapshot()
}
