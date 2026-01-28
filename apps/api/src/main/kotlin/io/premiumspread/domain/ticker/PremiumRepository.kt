package io.premiumspread.domain.ticker

import java.time.Instant

interface PremiumRepository {
    fun save(premium: Premium): Premium
    fun findById(id: Long): Premium?
    fun findLatestBySymbol(symbol: Symbol): Premium?
    fun findAllBySymbolAndPeriod(symbol: Symbol, from: Instant, to: Instant): List<Premium>
}
