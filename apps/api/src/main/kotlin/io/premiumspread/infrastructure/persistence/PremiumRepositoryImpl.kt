package io.premiumspread.infrastructure.persistence

import io.premiumspread.domain.ticker.Premium
import io.premiumspread.domain.ticker.PremiumRepository
import io.premiumspread.domain.ticker.Symbol
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
class PremiumRepositoryImpl(
    private val premiumJpaRepository: PremiumJpaRepository,
) : PremiumRepository {

    override fun save(premium: Premium): Premium {
        return premiumJpaRepository.save(premium)
    }

    override fun findById(id: Long): Premium? {
        return premiumJpaRepository.findByIdOrNull(id)
    }

    override fun findLatestBySymbol(symbol: Symbol): Premium? {
        return premiumJpaRepository.findLatestBySymbol(symbol.code)
    }

    override fun findAllBySymbolAndPeriod(symbol: Symbol, from: Instant, to: Instant): List<Premium> {
        return premiumJpaRepository.findAllBySymbolAndPeriod(symbol.code, from, to)
    }
}
