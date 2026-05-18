package io.premiumspread.application.position

import io.premiumspread.domain.position.Position
import io.premiumspread.domain.position.PositionPnl
import io.premiumspread.domain.position.PositionStatus
import io.premiumspread.domain.ticker.Exchange
import java.math.BigDecimal
import java.time.Instant

class PositionCriteria private constructor() {
    data class Open(
        val memberId: Long,
        val symbol: String,
        val koreaExchange: Exchange,
        val koreaQuantity: BigDecimal,
        val koreaEntryPrice: BigDecimal,
        val foreignExchange: Exchange,
        val foreignQuantity: BigDecimal,
        val foreignEntryPrice: BigDecimal,
        val foreignLeverage: Int,
        val entryFxRate: BigDecimal,
        val entryObservedAt: Instant,
    )
}

class PositionResult private constructor() {
    data class Detail(
        val id: Long,
        val memberId: Long,
        val symbol: String,
        val koreaExchange: Exchange,
        val koreaQuantity: BigDecimal,
        val koreaEntryPrice: BigDecimal,
        val foreignExchange: Exchange,
        val foreignQuantity: BigDecimal,
        val foreignEntryPrice: BigDecimal,
        val foreignLeverage: Int,
        val entryFxRate: BigDecimal,
        val entryPremiumRate: BigDecimal,
        val entryObservedAt: Instant,
        val status: PositionStatus,
    ) {
        companion object {
            fun from(position: Position): Detail = Detail(
                id = position.id,
                memberId = position.memberId,
                symbol = position.symbol.code,
                koreaExchange = position.koreaExchange,
                koreaQuantity = position.koreaQuantity,
                koreaEntryPrice = position.koreaEntryPrice,
                foreignExchange = position.foreignExchange,
                foreignQuantity = position.foreignQuantity,
                foreignEntryPrice = position.foreignEntryPrice,
                foreignLeverage = position.foreignLeverage,
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

    data class Summary(
        val totalPositions: Int,
        val openPositions: Int,
        val closedPositions: Int,
    )
}
