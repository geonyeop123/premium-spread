package io.premiumspread.application.position

import java.math.BigDecimal
import java.time.Instant

class PositionCriteria private constructor() {
    data class OpenAuto(
        val memberId: Long,
        val symbol: String,
        val koreaExchange: String,
        val koreaQuantity: BigDecimal,
        val foreignExchange: String,
        val foreignQuantity: BigDecimal,
        val foreignLeverage: Int,
    )

    data class OpenManual(
        val memberId: Long,
        val symbol: String,
        val koreaExchange: String,
        val koreaQuantity: BigDecimal,
        val koreaEntryPrice: BigDecimal,
        val foreignExchange: String,
        val foreignQuantity: BigDecimal,
        val foreignEntryPrice: BigDecimal,
        val foreignLeverage: Int,
        val entryFxRate: BigDecimal,
        val entryObservedAt: Instant,
    )

    data class FindById(val positionId: Long, val memberId: Long)
    data class FindAllOpen(val memberId: Long)
    data class FindAllClosed(val memberId: Long)
    data class CalculatePnl(val positionId: Long, val memberId: Long)
    data class Summary(val memberId: Long)
    data class Close(val positionId: Long, val memberId: Long)
}

class PositionResult private constructor() {
    data class Detail(
        val id: Long,
        val memberId: Long,
        val symbol: String,
        val koreaExchange: String,
        val koreaQuantity: BigDecimal,
        val koreaEntryPrice: BigDecimal,
        val foreignExchange: String,
        val foreignQuantity: BigDecimal,
        val foreignEntryPrice: BigDecimal,
        val foreignLeverage: Int,
        val entryFxRate: BigDecimal,
        val entryPremiumRate: BigDecimal,
        val entryObservedAt: Instant,
        val status: String,
    )

    data class Details(val items: List<Detail>)

    data class Pnl(
        val positionId: Long,
        val premiumDiff: BigDecimal,
        val entryPremiumRate: BigDecimal,
        val currentPremiumRate: BigDecimal,
        val koreaPnl: BigDecimal,
        val foreignPnlKrw: BigDecimal,
        val totalPnlKrw: BigDecimal,
        val koreaCurrentValue: BigDecimal,
        val totalPnlPercent: BigDecimal,
        val isProfit: Boolean,
        val calculatedAt: Instant,
    )

    data class Summary(
        val totalPositions: Int,
        val openPositions: Int,
        val closedPositions: Int,
    )
}
