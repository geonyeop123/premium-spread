package io.premiumspread.application.position

import io.premiumspread.domain.position.Position
import io.premiumspread.domain.position.PositionPnl
import io.premiumspread.domain.position.PositionStatus
import io.premiumspread.domain.ticker.Exchange
import java.math.BigDecimal
import java.time.Instant

class PositionCriteria private constructor() {
    data class Open(
        val symbol: String,
        val exchange: Exchange,
        val quantity: BigDecimal,
        val entryPrice: BigDecimal,
        val entryFxRate: BigDecimal,
        val entryPremiumRate: BigDecimal,
        val entryObservedAt: Instant,
    )
}

class PositionResult private constructor() {
    data class Detail(
        val id: Long,
        val symbol: String,
        val exchange: Exchange,
        val quantity: BigDecimal,
        val entryPrice: BigDecimal,
        val entryFxRate: BigDecimal,
        val entryPremiumRate: BigDecimal,
        val entryObservedAt: Instant,
        val status: PositionStatus,
    ) {
        companion object {
            fun from(position: Position): Detail = Detail(
                id = position.id,
                symbol = position.symbol.code,
                exchange = position.exchange,
                quantity = position.quantity,
                entryPrice = position.entryPrice,
                entryFxRate = position.entryFxRate,
                entryPremiumRate = position.entryPremiumRate,
                entryObservedAt = position.entryObservedAt,
                status = position.status,
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
            fun from(positionId: Long, pnl: PositionPnl): Pnl = Pnl(
                positionId = positionId,
                premiumDiff = pnl.premiumDiff,
                entryPremiumRate = pnl.entryPremiumRate,
                currentPremiumRate = pnl.currentPremiumRate,
                isProfit = pnl.isProfit(),
                calculatedAt = pnl.calculatedAt,
            )
        }
    }
}
