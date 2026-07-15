package io.premiumspread.domain.premium

import io.premiumspread.domain.market.MarketPair
import java.time.Instant

interface PremiumRepository {
    fun save(premium: Premium): Premium

    fun findById(id: Long): Premium?

    fun findLatestByPair(pair: MarketPair): Premium?

    fun findLatestSnapshotByPair(pair: MarketPair): PremiumSnapshot?

    fun findAllByPair(pair: MarketPair, from: Instant, to: Instant): List<Premium>

    fun findAggregationByPair(
        pair: MarketPair,
        interval: String,
        from: Instant,
        to: Instant,
    ): List<PremiumAggregationSnapshot>
}
