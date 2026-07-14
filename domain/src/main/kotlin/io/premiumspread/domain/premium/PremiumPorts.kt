package io.premiumspread.domain.premium

import io.premiumspread.domain.market.MarketPair
import java.time.Instant

interface PremiumReadPort {
    fun findLatest(pair: MarketPair): PremiumSnapshot?

    fun findAll(pair: MarketPair, from: Instant, to: Instant): List<PremiumSnapshot>
}

interface PremiumWritePort {
    fun save(snapshot: PremiumSnapshot)
}
