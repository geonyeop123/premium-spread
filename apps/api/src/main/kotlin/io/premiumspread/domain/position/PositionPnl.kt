package io.premiumspread.domain.position

import java.math.BigDecimal
import java.time.Instant

data class PositionPnl(
    val premiumDiff: BigDecimal,
    val entryPremiumRate: BigDecimal,
    val currentPremiumRate: BigDecimal,
    val calculatedAt: Instant,
) {
    fun isProfit(): Boolean = premiumDiff < BigDecimal.ZERO
}
