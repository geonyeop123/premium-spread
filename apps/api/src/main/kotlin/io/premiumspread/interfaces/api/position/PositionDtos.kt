package io.premiumspread.interfaces.api.position

import io.premiumspread.application.position.PositionResult
import java.math.BigDecimal
import java.time.Instant

class PositionRequest private constructor() {
    data class Open(
        val symbol: String,
        val exchange: String,
        val quantity: BigDecimal,
        val entryPrice: BigDecimal,
        val entryFxRate: BigDecimal,
        val entryPremiumRate: BigDecimal,
        val entryObservedAt: Instant,
    )
}

class PositionResponse private constructor() {
    data class Detail(
        val id: Long,
        val symbol: String,
        val exchange: String,
        val quantity: BigDecimal,
        val entryPrice: BigDecimal,
        val entryFxRate: BigDecimal,
        val entryPremiumRate: BigDecimal,
        val entryObservedAt: Instant,
        val status: String,
    ) {
        companion object {
            fun from(result: PositionResult.Detail): Detail = Detail(
                id = result.id,
                symbol = result.symbol,
                exchange = result.exchange.name,
                quantity = result.quantity,
                entryPrice = result.entryPrice,
                entryFxRate = result.entryFxRate,
                entryPremiumRate = result.entryPremiumRate,
                entryObservedAt = result.entryObservedAt,
                status = result.status.name,
            )
        }
    }

    data class Pnl(
        val positionId: Long,
        val premiumDiff: BigDecimal,
        val entryPremiumRate: BigDecimal,
        val currentPremiumRate: BigDecimal,
        val isProfit: Boolean,
        val calculatedAt: Instant,
    ) {
        companion object {
            fun from(result: PositionResult.Pnl): Pnl = Pnl(
                positionId = result.positionId,
                premiumDiff = result.premiumDiff,
                entryPremiumRate = result.entryPremiumRate,
                currentPremiumRate = result.currentPremiumRate,
                isProfit = result.isProfit,
                calculatedAt = result.calculatedAt,
            )
        }
    }
}
