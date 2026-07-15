package io.premiumspread.interfaces.api.position

import io.premiumspread.application.position.PositionResult
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import java.math.BigDecimal
import java.time.Instant

class PositionRequest private constructor() {
    data class OpenAuto(
        @field:NotBlank val symbol: String,
        @field:NotBlank val koreaExchange: String,
        @field:Positive val koreaQuantity: BigDecimal,
        @field:NotBlank val foreignExchange: String,
        @field:Positive val foreignQuantity: BigDecimal,
        @field:Positive val foreignLeverage: Int,
    )

    data class OpenManual(
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

class PositionResponse private constructor() {
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
    ) {
        companion object {
            fun from(result: PositionResult.Detail): Detail = Detail(
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
            )
        }
    }

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
    ) {
        companion object {
            fun from(result: PositionResult.Pnl): Pnl = Pnl(
                positionId = result.positionId,
                premiumDiff = result.premiumDiff,
                entryPremiumRate = result.entryPremiumRate,
                currentPremiumRate = result.currentPremiumRate,
                koreaPnl = result.koreaPnl,
                foreignPnlKrw = result.foreignPnlKrw,
                totalPnlKrw = result.totalPnlKrw,
                koreaCurrentValue = result.koreaCurrentValue,
                totalPnlPercent = result.totalPnlPercent,
                isProfit = result.isProfit,
                calculatedAt = result.calculatedAt,
            )
        }
    }

    data class Summary(val totalPositions: Int, val openPositions: Int, val closedPositions: Int) {
        companion object {
            fun from(result: PositionResult.Summary): Summary = Summary(
                totalPositions = result.totalPositions,
                openPositions = result.openPositions,
                closedPositions = result.closedPositions,
            )
        }
    }
}
