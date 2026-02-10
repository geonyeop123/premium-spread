package io.premiumspread.domain.ticker

import java.math.BigDecimal
import java.time.Instant

data class TickerSnapshot(
    val exchange: String,
    val symbol: String,
    val currency: String,
    val price: BigDecimal,
    val volume: BigDecimal?,
    val observedAt: Instant,
)
