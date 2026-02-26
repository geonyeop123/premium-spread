package io.premiumspread.domain.premium

import java.math.BigDecimal
import java.time.Instant

data class PremiumAggregationSnapshot(
    val symbol: String,
    val high: BigDecimal,
    val low: BigDecimal,
    val open: BigDecimal,
    val close: BigDecimal,
    val avg: BigDecimal,
    val count: Int,
    val observedAt: Instant,
)
