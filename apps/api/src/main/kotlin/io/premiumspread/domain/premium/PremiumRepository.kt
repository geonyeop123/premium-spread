package io.premiumspread.domain.premium

import io.premiumspread.domain.ticker.Symbol
import java.time.Instant

interface PremiumRepository {
    fun save(premium: Premium): Premium
    fun findById(id: Long): Premium?
    fun findLatestBySymbol(symbol: Symbol): Premium?
    fun findLatestSnapshotBySymbol(symbol: Symbol): PremiumSnapshot?
    fun findAllBySymbolAndPeriod(symbol: Symbol, from: Instant, to: Instant): List<Premium>
}
