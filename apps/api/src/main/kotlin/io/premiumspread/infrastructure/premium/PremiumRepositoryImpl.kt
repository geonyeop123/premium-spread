package io.premiumspread.infrastructure.premium

import io.premiumspread.domain.premium.Premium
import io.premiumspread.domain.premium.PremiumRepository
import io.premiumspread.domain.premium.PremiumSnapshot
import io.premiumspread.domain.ticker.Symbol
import org.slf4j.LoggerFactory
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
class PremiumRepositoryImpl(
    private val premiumJpaRepository: PremiumJpaRepository,
    private val premiumCacheReader: PremiumCacheReader,
) : PremiumRepository {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun save(premium: Premium): Premium {
        return premiumJpaRepository.save(premium)
    }

    override fun findById(id: Long): Premium? {
        return premiumJpaRepository.findByIdOrNull(id)
    }

    override fun findLatestBySymbol(symbol: Symbol): Premium? {
        return premiumJpaRepository.findLatestBySymbol(symbol.code)
    }

    override fun findLatestSnapshotBySymbol(symbol: Symbol): PremiumSnapshot? {
        val cached = premiumCacheReader.get(symbol.code)
        if (cached != null) {
            log.debug("Premium snapshot cache hit: {}", symbol.code)
            return PremiumSnapshot(
                symbol = cached.symbol,
                premiumRate = cached.premiumRate,
                koreaPrice = cached.koreaPrice,
                foreignPrice = cached.foreignPrice,
                foreignPriceInKrw = cached.foreignPriceInKrw,
                fxRate = cached.fxRate,
                observedAt = cached.observedAt,
            )
        }

        log.debug("Premium snapshot cache miss, falling back to DB JOIN query: {}", symbol.code)
        return premiumJpaRepository.findLatestSnapshotBySymbol(symbol.code)
    }

    override fun findAllBySymbolAndPeriod(symbol: Symbol, from: Instant, to: Instant): List<Premium> {
        return premiumJpaRepository.findAllBySymbolAndPeriod(symbol.code, from, to)
    }
}
