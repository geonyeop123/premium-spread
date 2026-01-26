package io.premiumspread.domain.ticker

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

data class Premium(
    val koreaTickerId: Long,
    val foreignTickerId: Long,
    val fxTickerId: Long,
    val premiumRate: BigDecimal,
    val observedAt: Instant,
) {
    companion object {
        private val premiumRateScale = 2
        private val divisionScale = 10
        private val hundred = BigDecimal("100")

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
                .divide(foreignPriceInKrw, divisionScale, RoundingMode.HALF_UP)
                .multiply(hundred)
                .setScale(premiumRateScale, RoundingMode.HALF_UP)
        }

        private fun latestObservedAt(first: Instant, second: Instant, third: Instant): Instant {
            return maxOf(first, second, third)
        }
    }
}
