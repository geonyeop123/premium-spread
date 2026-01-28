package io.premiumspread.domain.position

import io.premiumspread.domain.BaseEntity
import io.premiumspread.domain.ticker.Exchange
import io.premiumspread.domain.ticker.ExchangeRegion
import io.premiumspread.domain.ticker.Symbol
import jakarta.persistence.AttributeOverride
import jakarta.persistence.Column
import jakarta.persistence.Embedded
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant

@Entity
@Table(name = "position")
class Position private constructor(
    @Embedded
    @AttributeOverride(name = "code", column = Column(name = "symbol"))
    val symbol: Symbol,

    @Enumerated(EnumType.STRING)
    @Column(name = "exchange", nullable = false)
    val exchange: Exchange,

    @Column(name = "quantity", nullable = false, precision = 30, scale = 10)
    val quantity: BigDecimal,

    @Column(name = "entry_price", nullable = false, precision = 30, scale = 10)
    val entryPrice: BigDecimal,

    @Column(name = "entry_fx_rate", nullable = false, precision = 20, scale = 6)
    val entryFxRate: BigDecimal,

    @Column(name = "entry_premium_rate", nullable = false, precision = 10, scale = 2)
    val entryPremiumRate: BigDecimal,

    @Column(name = "entry_observed_at", nullable = false)
    val entryObservedAt: Instant,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    var status: PositionStatus = PositionStatus.OPEN,
) : BaseEntity() {

    fun calculatePremiumDiff(currentPremiumRate: BigDecimal): PositionPnl {
        val premiumDiff = currentPremiumRate.subtract(entryPremiumRate)
        return PositionPnl(
            premiumDiff = premiumDiff,
            entryPremiumRate = entryPremiumRate,
            currentPremiumRate = currentPremiumRate,
            calculatedAt = Instant.now(),
        )
    }

    fun close() {
        if (status == PositionStatus.CLOSED) {
            throw InvalidPositionException("Position is already closed.")
        }
        status = PositionStatus.CLOSED
    }

    companion object {
        fun create(
            symbol: Symbol,
            exchange: Exchange,
            quantity: BigDecimal,
            entryPrice: BigDecimal,
            entryFxRate: BigDecimal,
            entryPremiumRate: BigDecimal,
            entryObservedAt: Instant,
        ): Position {
            validateKoreaExchange(exchange)
            validatePositiveQuantity(quantity)
            validatePositiveEntryPrice(entryPrice)
            validatePositiveFxRate(entryFxRate)

            return Position(
                symbol = symbol,
                exchange = exchange,
                quantity = quantity,
                entryPrice = entryPrice,
                entryFxRate = entryFxRate,
                entryPremiumRate = entryPremiumRate,
                entryObservedAt = entryObservedAt,
            )
        }

        private fun validateKoreaExchange(exchange: Exchange) {
            if (exchange.region != ExchangeRegion.KOREA) {
                throw InvalidPositionException("Position exchange must be KOREA region.")
            }
        }

        private fun validatePositiveQuantity(quantity: BigDecimal) {
            if (quantity <= BigDecimal.ZERO) {
                throw InvalidPositionException("Position quantity must be positive.")
            }
        }

        private fun validatePositiveEntryPrice(entryPrice: BigDecimal) {
            if (entryPrice <= BigDecimal.ZERO) {
                throw InvalidPositionException("Position entryPrice must be positive.")
            }
        }

        private fun validatePositiveFxRate(entryFxRate: BigDecimal) {
            if (entryFxRate <= BigDecimal.ZERO) {
                throw InvalidPositionException("Position entryFxRate must be positive.")
            }
        }
    }
}
