package io.premiumspread.domain.tracking

import io.premiumspread.domain.BaseEntity
import io.premiumspread.domain.market.MarketPair
import io.premiumspread.domain.premium.PremiumPolicy
import io.premiumspread.domain.ticker.Exchange
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

data class TrackingRecordSpec(
    val memberId: Long,
    val pair: MarketPair,
    val koreaQuantity: BigDecimal,
    val koreaEntryPrice: BigDecimal,
    val foreignQuantity: BigDecimal,
    val foreignEntryPrice: BigDecimal,
    val foreignLeverage: Int,
    val entryFxRate: BigDecimal,
    val entryObservedAt: Instant,
)

// JPA aggregate state stays explicit so persistence and domain invariants share one constructor.
@Suppress("LongParameterList")
@Entity
@Table(name = "position")
class Tracking private constructor(
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
    var status: TrackingStatus = TrackingStatus.OPEN,

    @Column(name = "member_id", nullable = false)
    val memberId: Long,
) : BaseEntity() {

    val pair: MarketPair
        get() = MarketPair(symbol, koreaExchange, foreignExchange)

    fun calculatePnl(
        currentKoreaPrice: BigDecimal,
        currentForeignPrice: BigDecimal,
        currentFxRate: BigDecimal,
        currentPremiumRate: BigDecimal,
        calculatedAt: Instant,
    ): TrackingGrossPnl {
        require(currentKoreaPrice > BigDecimal.ZERO) { "currentKoreaPrice must be positive" }
        require(currentForeignPrice > BigDecimal.ZERO) { "currentForeignPrice must be positive" }
        require(currentFxRate > BigDecimal.ZERO) { "currentFxRate must be positive" }

        val koreaPnl = currentKoreaPrice
            .subtract(koreaEntryPrice)
            .multiply(koreaQuantity)

        val foreignPnlUsd = foreignEntryPrice
            .subtract(currentForeignPrice)
            .multiply(foreignQuantity)

        val foreignPnlKrw = foreignPnlUsd.multiply(currentFxRate)
        val totalPnlKrw = koreaPnl.add(foreignPnlKrw)
        val koreaCurrentValue = currentKoreaPrice.multiply(koreaQuantity)
        val totalPnlPercent = totalPnlKrw
            .divide(koreaCurrentValue, 10, RoundingMode.HALF_UP)
            .multiply(BigDecimal(100))
            .setScale(2, RoundingMode.HALF_UP)
        val premiumDiff = currentPremiumRate.subtract(entryPremiumRate)

        return TrackingGrossPnl(
            premiumDiff = premiumDiff,
            entryPremiumRate = entryPremiumRate,
            currentPremiumRate = currentPremiumRate,
            koreaPnl = koreaPnl,
            foreignPnlKrw = foreignPnlKrw,
            totalPnlKrw = totalPnlKrw,
            koreaCurrentValue = koreaCurrentValue,
            totalPnlPercent = totalPnlPercent,
            calculatedAt = calculatedAt,
        )
    }

    fun close() {
        if (status == TrackingStatus.CLOSED) {
            throw InvalidTrackingException("Tracking is already closed.")
        }
        status = TrackingStatus.CLOSED
    }

    companion object {
        fun create(spec: TrackingRecordSpec): Tracking {
            validatePositive("koreaQuantity", spec.koreaQuantity)
            validatePositive("koreaEntryPrice", spec.koreaEntryPrice)
            validatePositive("foreignQuantity", spec.foreignQuantity)
            validatePositive("foreignEntryPrice", spec.foreignEntryPrice)
            validatePositive("entryFxRate", spec.entryFxRate)
            validateLeverage(spec.foreignLeverage)

            val entryPremiumRate = PremiumPolicy.calculate(
                koreaPrice = spec.koreaEntryPrice,
                foreignPriceUsd = spec.foreignEntryPrice,
                fxRate = spec.entryFxRate,
            ).entityPremiumRate

            return Tracking(
                symbol = spec.pair.symbol,
                koreaExchange = spec.pair.koreaExchange,
                koreaQuantity = spec.koreaQuantity,
                koreaEntryPrice = spec.koreaEntryPrice,
                foreignExchange = spec.pair.foreignExchange,
                foreignQuantity = spec.foreignQuantity,
                foreignEntryPrice = spec.foreignEntryPrice,
                foreignLeverage = spec.foreignLeverage,
                entryFxRate = spec.entryFxRate,
                entryPremiumRate = entryPremiumRate,
                entryObservedAt = spec.entryObservedAt,
                memberId = spec.memberId,
            )
        }

        private fun validatePositive(name: String, value: BigDecimal) {
            if (value <= BigDecimal.ZERO) {
                throw InvalidTrackingException("Tracking $name must be positive.")
            }
        }

        private fun validateLeverage(leverage: Int) {
            if (leverage < 1 || leverage > 125) {
                throw InvalidTrackingException("Foreign leverage must be between 1 and 125.")
            }
        }
    }
}
