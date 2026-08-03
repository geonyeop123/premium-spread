package io.premiumspread.application.tracking

import java.math.BigDecimal
import java.time.Instant

class TrackingCriteria private constructor() {
    data class RecordFromMarket(
        val memberId: Long,
        val symbol: String,
        val koreaExchange: String,
        val koreaQuantity: BigDecimal,
        val foreignExchange: String,
        val foreignQuantity: BigDecimal,
        val foreignLeverage: Int,
    )

    data class Record(
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

    data class FindById(val trackingId: Long, val memberId: Long)
    data class FindAllActive(val memberId: Long)
    data class FindAllArchived(val memberId: Long)
    data class CalculatePnl(val trackingId: Long, val memberId: Long)
    data class Summary(val memberId: Long)
    data class Archive(val trackingId: Long, val memberId: Long)
}

class TrackingResult private constructor() {
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
        val trackingId: Long,
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

    data class Summary(val totalTrackings: Int, val activeTrackings: Int, val archivedTrackings: Int)
}
