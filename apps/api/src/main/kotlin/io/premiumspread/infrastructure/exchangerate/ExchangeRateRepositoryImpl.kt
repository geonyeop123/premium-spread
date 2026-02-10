package io.premiumspread.infrastructure.exchangerate

import io.premiumspread.domain.exchangerate.ExchangeRateRepository
import io.premiumspread.domain.exchangerate.ExchangeRateSnapshot as DomainSnapshot
import io.premiumspread.infrastructure.cache.FxCacheReader
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Repository

@Repository
class ExchangeRateRepositoryImpl(
    private val fxCacheReader: FxCacheReader,
    private val exchangeRateQueryRepository: ExchangeRateQueryRepository,
) : ExchangeRateRepository {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun findLatestSnapshot(baseCurrency: String, quoteCurrency: String): DomainSnapshot? {
        // 1. 캐시에서 조회
        val cached = fxCacheReader.get(baseCurrency, quoteCurrency)
        if (cached != null) {
            log.debug("FX rate cache hit: {}/{}", baseCurrency, quoteCurrency)
            return DomainSnapshot(
                baseCurrency = cached.baseCurrency,
                quoteCurrency = cached.quoteCurrency,
                rate = cached.rate,
                observedAt = cached.timestamp,
            )
        }

        // 2. 캐시 미스 → exchange_rate 테이블
        log.debug("FX rate cache miss, falling back to DB: {}/{}", baseCurrency, quoteCurrency)
        val dbSnapshot = exchangeRateQueryRepository.findLatest(baseCurrency, quoteCurrency)
        if (dbSnapshot != null) {
            log.debug("FX rate found in DB: {}/{}", baseCurrency, quoteCurrency)
            return DomainSnapshot(
                baseCurrency = dbSnapshot.baseCurrency,
                quoteCurrency = dbSnapshot.quoteCurrency,
                rate = dbSnapshot.rate,
                observedAt = dbSnapshot.observedAt,
            )
        }

        log.warn("FX rate not found: {}/{}", baseCurrency, quoteCurrency)
        return null
    }
}
