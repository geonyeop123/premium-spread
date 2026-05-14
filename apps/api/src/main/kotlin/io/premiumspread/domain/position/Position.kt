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
import java.math.RoundingMode
import java.time.Instant

@Entity
@Table(name = "position")
class Position private constructor(
    @Embedded
    @AttributeOverride(name = "code", column = Column(name = "symbol"))
    val symbol: Symbol,

    @Enumerated(EnumType.STRING)
    @Column(name = "korea_exchange", nullable = false)
    val koreaExchange: Exchange,

    @Column(name = "korea_quantity", nullable = false, precision = 30, scale = 10)
    val koreaQuantity: BigDecimal,

    @Column(name = "korea_entry_price", nullable = false, precision = 30, scale = 10)
    val koreaEntryPrice: BigDecimal,

    @Enumerated(EnumType.STRING)
    @Column(name = "foreign_exchange", nullable = false)
    val foreignExchange: Exchange,

    @Column(name = "foreign_quantity", nullable = false, precision = 30, scale = 10)
    val foreignQuantity: BigDecimal,

    @Column(name = "foreign_entry_price", nullable = false, precision = 30, scale = 10)
    val foreignEntryPrice: BigDecimal,

    @Column(name = "foreign_leverage", nullable = false)
    val foreignLeverage: Int,

    @Column(name = "entry_fx_rate", nullable = false, precision = 20, scale = 6)
    val entryFxRate: BigDecimal,

    @Column(name = "entry_premium_rate", nullable = false, precision = 10, scale = 2)
    val entryPremiumRate: BigDecimal,

    @Column(name = "entry_observed_at", nullable = false)
    val entryObservedAt: Instant,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    var status: PositionStatus = PositionStatus.OPEN,

    @Column(name = "member_id", nullable = false)
    val memberId: Long,
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
            memberId: Long,
            symbol: Symbol,
            koreaExchange: Exchange,
            koreaQuantity: BigDecimal,
            koreaEntryPrice: BigDecimal,
            foreignExchange: Exchange,
            foreignQuantity: BigDecimal,
            foreignEntryPrice: BigDecimal,
            foreignLeverage: Int,
            entryFxRate: BigDecimal,
            entryObservedAt: Instant,
        ): Position {
            validateKoreaRegion(koreaExchange)
            validateForeignRegion(foreignExchange)
            validatePositive("koreaQuantity", koreaQuantity)
            validatePositive("koreaEntryPrice", koreaEntryPrice)
            validatePositive("foreignQuantity", foreignQuantity)
            validatePositive("foreignEntryPrice", foreignEntryPrice)
            validatePositive("entryFxRate", entryFxRate)
            validateLeverage(foreignLeverage)

            val entryPremiumRate = calculateEntryPremiumRate(
                koreaEntryPrice = koreaEntryPrice,
                foreignEntryPrice = foreignEntryPrice,
                entryFxRate = entryFxRate,
            )

            return Position(
                symbol = symbol,
                koreaExchange = koreaExchange,
                koreaQuantity = koreaQuantity,
                koreaEntryPrice = koreaEntryPrice,
                foreignExchange = foreignExchange,
                foreignQuantity = foreignQuantity,
                foreignEntryPrice = foreignEntryPrice,
                foreignLeverage = foreignLeverage,
                entryFxRate = entryFxRate,
                entryPremiumRate = entryPremiumRate,
                entryObservedAt = entryObservedAt,
                memberId = memberId,
            )
        }

        private fun validateKoreaRegion(exchange: Exchange) {
            if (exchange.region != ExchangeRegion.KOREA) {
                throw InvalidPositionException("Korea exchange must be KOREA region.")
            }
        }

        private fun validateForeignRegion(exchange: Exchange) {
            if (exchange.region != ExchangeRegion.FOREIGN) {
                throw InvalidPositionException("Foreign exchange must be FOREIGN region.")
            }
        }

        private fun validatePositive(name: String, value: BigDecimal) {
            if (value <= BigDecimal.ZERO) {
                throw InvalidPositionException("Position $name must be positive.")
            }
        }

        private fun validateLeverage(leverage: Int) {
            if (leverage < 1 || leverage > 125) {
                throw InvalidPositionException("Foreign leverage must be between 1 and 125.")
            }
        }

        private fun calculateEntryPremiumRate(
            koreaEntryPrice: BigDecimal,
            foreignEntryPrice: BigDecimal,
            entryFxRate: BigDecimal,
        ): BigDecimal {
            val foreignKrw = foreignEntryPrice.multiply(entryFxRate)
            return koreaEntryPrice
                .subtract(foreignKrw)
                .divide(foreignKrw, 10, RoundingMode.HALF_UP)
                .multiply(BigDecimal(100))
                .setScale(2, RoundingMode.HALF_UP)
        }
    }
}
