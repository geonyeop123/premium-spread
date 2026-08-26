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
    data class GetGrossPnl(val trackingId: Long, val memberId: Long)
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
        // 종료 정보 — ACTIVE 는 전부 null/false 다 (design.md §5.3.3-1)
        val closedAt: Instant?,
        val closePriceSource: String?,
        val hasConfirmedClose: Boolean,
    )

    data class Details(val items: List<Detail>)

    /**
     * gross 손익. 필드명이 gross 여부와 백분율 분모를 스스로 말한다 (design.md §5.3.3, SEM-4).
     */
    data class GrossPnl(
        val trackingId: Long,
        val priceBasis: String,
        val pnlBasis: String,
        val entryPremiumRate: BigDecimal,
        val referencePremiumRate: BigDecimal,
        val premiumRateDelta: BigDecimal,
        val koreaLegGrossPnlKrw: BigDecimal,
        val foreignLegGrossPnlKrw: BigDecimal,
        val totalGrossPnlKrw: BigDecimal,
        val koreaLegNotionalKrw: BigDecimal,
        val grossPnlPercentOfKoreaNotional: BigDecimal,
        val isGrossProfit: Boolean,
        val calculatedAt: Instant,
        val observedAt: Instant,
        val fxObservedAt: Instant,
    ) {
        companion object {
            const val PNL_BASIS = "GROSS_EXCLUDING_FEES_FUNDING_SLIPPAGE_FX_SPREAD"
        }
    }

    data class Summary(val totalTrackings: Int, val activeTrackings: Int, val archivedTrackings: Int)
}
