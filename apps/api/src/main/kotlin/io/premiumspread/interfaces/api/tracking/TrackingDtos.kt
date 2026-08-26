package io.premiumspread.interfaces.api.tracking

import io.premiumspread.application.tracking.TrackingResult
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import java.math.BigDecimal
import java.time.Instant

class TrackingRequest private constructor() {
    data class RecordFromMarket(
        @field:NotBlank val symbol: String,
        @field:NotBlank val koreaExchange: String,
        @field:Positive val koreaQuantity: BigDecimal,
        @field:NotBlank val foreignExchange: String,
        @field:Positive val foreignQuantity: BigDecimal,
        @field:Positive val foreignLeverage: Int,
    )

    data class Record(
        @field:NotBlank val symbol: String,
        @field:NotBlank val koreaExchange: String,
        @field:Positive val koreaQuantity: BigDecimal,
        @field:Positive val koreaEntryPrice: BigDecimal,
        @field:NotBlank val foreignExchange: String,
        @field:Positive val foreignQuantity: BigDecimal,
        @field:Positive val foreignEntryPrice: BigDecimal,
        @field:Positive val foreignLeverage: Int,
        @field:Positive val entryFxRate: BigDecimal,
        val entryObservedAt: Instant,
    )
}

class TrackingResponse private constructor() {
    data class Detail(
        val id: Long,
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
        val closedAt: Instant?,
        val closePriceSource: String?,
        val hasConfirmedClose: Boolean,
    ) {
        companion object {
            fun from(result: TrackingResult.Detail): Detail = Detail(
                id = result.id,
                symbol = result.symbol,
                koreaExchange = result.koreaExchange,
                koreaQuantity = result.koreaQuantity,
                koreaEntryPrice = result.koreaEntryPrice,
                foreignExchange = result.foreignExchange,
                foreignQuantity = result.foreignQuantity,
                foreignEntryPrice = result.foreignEntryPrice,
                foreignLeverage = result.foreignLeverage,
                entryFxRate = result.entryFxRate,
                entryPremiumRate = result.entryPremiumRate,
                entryObservedAt = result.entryObservedAt,
                status = result.status,
                closedAt = result.closedAt,
                closePriceSource = result.closePriceSource,
                hasConfirmedClose = result.hasConfirmedClose,
            )
        }
    }

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
            fun from(result: TrackingResult.GrossPnl): GrossPnl = GrossPnl(
                trackingId = result.trackingId,
                priceBasis = result.priceBasis,
                pnlBasis = result.pnlBasis,
                entryPremiumRate = result.entryPremiumRate,
                referencePremiumRate = result.referencePremiumRate,
                premiumRateDelta = result.premiumRateDelta,
                koreaLegGrossPnlKrw = result.koreaLegGrossPnlKrw,
                foreignLegGrossPnlKrw = result.foreignLegGrossPnlKrw,
                totalGrossPnlKrw = result.totalGrossPnlKrw,
                koreaLegNotionalKrw = result.koreaLegNotionalKrw,
                grossPnlPercentOfKoreaNotional = result.grossPnlPercentOfKoreaNotional,
                isGrossProfit = result.isGrossProfit,
                calculatedAt = result.calculatedAt,
                observedAt = result.observedAt,
                fxObservedAt = result.fxObservedAt,
            )
        }
    }

    data class Summary(val totalTrackings: Int, val activeTrackings: Int, val archivedTrackings: Int) {
        companion object {
            fun from(result: TrackingResult.Summary): Summary = Summary(
                totalTrackings = result.totalTrackings,
                activeTrackings = result.activeTrackings,
                archivedTrackings = result.archivedTrackings,
            )
        }
    }
}
