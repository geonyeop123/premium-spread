package io.premiumspread.domain.tracking

import java.math.BigDecimal
import java.time.Instant

data class TrackingGrossPnl(
    val premiumDiff: BigDecimal,
    val entryPremiumRate: BigDecimal,
    val currentPremiumRate: BigDecimal,
    val koreaPnl: BigDecimal,
    val foreignPnlKrw: BigDecimal,
    val totalPnlKrw: BigDecimal,
    val koreaCurrentValue: BigDecimal,
    val totalPnlPercent: BigDecimal,
    val calculatedAt: Instant,
) {
    fun isProfit(): Boolean = totalPnlKrw > BigDecimal.ZERO
}
