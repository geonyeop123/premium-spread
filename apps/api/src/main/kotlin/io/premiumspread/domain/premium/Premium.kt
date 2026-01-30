package io.premiumspread.domain.premium

import io.premiumspread.domain.BaseEntity
import io.premiumspread.domain.InvalidPremiumInputException
import io.premiumspread.domain.ticker.Currency
import io.premiumspread.domain.ticker.ExchangeRegion
import io.premiumspread.domain.ticker.Symbol
import io.premiumspread.domain.ticker.Ticker
import jakarta.persistence.AttributeOverride
import jakarta.persistence.Column
import jakarta.persistence.Embedded
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

@Entity
@Table(name = "premium")
class Premium private constructor(
    @Embedded
    @AttributeOverride(name = "code", column = Column(name = "symbol"))
    val symbol: Symbol,

    @Column(name = "korea_ticker_id", nullable = false)
    val koreaTickerId: Long,

    @Column(name = "foreign_ticker_id", nullable = false)
    val foreignTickerId: Long,

    @Column(name = "fx_ticker_id", nullable = false)
    val fxTickerId: Long,

    @Column(name = "premium_rate", nullable = false, precision = 10, scale = 2)
    val premiumRate: BigDecimal,

    @Column(name = "observed_at", nullable = false)
    val observedAt: Instant,
) : BaseEntity() {

    companion object {
        private const val PREMIUM_RATE_SCALE = 2
        private const val DIVISION_SCALE = 10
        private val HUNDRED = BigDecimal("100")

        fun create(
            koreaTicker: Ticker,
            foreignTicker: Ticker,
            fxTicker: Ticker,
        ): Premium {
            validateRegions(koreaTicker, foreignTicker)
            val koreaSymbol = koreaTicker.quote.baseSymbolOrNull()
                ?: throw InvalidPremiumInputException("Korea ticker must be SYMBOL/KRW.")
            val foreignSymbol = foreignTicker.quote.baseSymbolOrNull()
                ?: throw InvalidPremiumInputException("Foreign ticker must be SYMBOL/USD.")

            if (koreaSymbol != foreignSymbol) {
                throw InvalidPremiumInputException("Ticker symbols must match.")
            }

            if (koreaTicker.quote.currency != Currency.KRW) {
                throw InvalidPremiumInputException("Korea ticker must be quoted in KRW.")
            }
            if (foreignTicker.quote.currency != Currency.USD) {
                throw InvalidPremiumInputException("Foreign ticker must be quoted in USD.")
            }

            val fxBase = fxTicker.quote.baseCurrencyOrNull()
                ?: throw InvalidPremiumInputException("FX ticker must be USD/KRW.")
            if (fxBase != Currency.USD || fxTicker.quote.currency != Currency.KRW) {
                throw InvalidPremiumInputException("FX ticker must be USD/KRW.")
            }

            val premiumRate = calculatePremiumRate(
                koreaPrice = koreaTicker.price,
                foreignPriceUsd = foreignTicker.price,
                fxRate = fxTicker.price,
            )

            return Premium(
                symbol = koreaSymbol,
                koreaTickerId = koreaTicker.id,
                foreignTickerId = foreignTicker.id,
                fxTickerId = fxTicker.id,
                premiumRate = premiumRate,
                observedAt = latestObservedAt(
                    koreaTicker.observedAt,
                    foreignTicker.observedAt,
                    fxTicker.observedAt,
                ),
            )
        }

        private fun validateRegions(koreaTicker: Ticker, foreignTicker: Ticker) {
            if (koreaTicker.exchangeRegion != ExchangeRegion.KOREA) {
                throw InvalidPremiumInputException("Korea ticker must have KOREA region.")
            }
            if (foreignTicker.exchangeRegion != ExchangeRegion.FOREIGN) {
                throw InvalidPremiumInputException("Foreign ticker must have FOREIGN region.")
            }
        }

        private fun calculatePremiumRate(
            koreaPrice: BigDecimal,
            foreignPriceUsd: BigDecimal,
            fxRate: BigDecimal,
        ): BigDecimal {
            val foreignPriceInKrw = foreignPriceUsd.multiply(fxRate)
            val diff = koreaPrice.subtract(foreignPriceInKrw)
            return diff
                .divide(foreignPriceInKrw, DIVISION_SCALE, RoundingMode.HALF_UP)
                .multiply(HUNDRED)
                .setScale(PREMIUM_RATE_SCALE, RoundingMode.HALF_UP)
        }

        private fun latestObservedAt(first: Instant, second: Instant, third: Instant): Instant {
            return maxOf(first, second, third)
        }
    }
}
