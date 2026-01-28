package io.premiumspread.domain.position

import io.premiumspread.domain.ticker.Exchange
import java.math.BigDecimal
import java.time.Instant

class PositionCommand private constructor() {
    data class Create(
        val symbol: String,
        val exchange: Exchange,
        val quantity: BigDecimal,
        val entryPrice: BigDecimal,
        val entryFxRate: BigDecimal,
        val entryPremiumRate: BigDecimal,
        val entryObservedAt: Instant,
    )
}
