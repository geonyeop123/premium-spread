package io.premiumspread.domain.premium

import io.premiumspread.domain.market.MarketPair
import io.premiumspread.domain.ticker.Exchange
import io.premiumspread.domain.ticker.Symbol
import java.math.BigDecimal
import java.time.Instant

data class PremiumSnapshot(
    val pair: MarketPair,
    val premiumRate: BigDecimal,
    val koreaPrice: BigDecimal,
    val foreignPrice: BigDecimal,
    val foreignPriceInKrw: BigDecimal,
    val fxRate: BigDecimal,
    val observedAt: Instant,
    val fxSource: Exchange = Exchange.FX_PROVIDER,
    val fxObservedAt: Instant = observedAt,
) {
    val symbol: String
        get() = pair.symbol.code

    val apiDisplayPremiumRate: BigDecimal
        get() = PremiumPolicy.normalizeDisplay(premiumRate)

    constructor(
        symbol: String,
        premiumRate: BigDecimal,
        koreaPrice: BigDecimal,
        foreignPrice: BigDecimal,
        foreignPriceInKrw: BigDecimal,
        fxRate: BigDecimal,
        observedAt: Instant,
        fxSource: Exchange = Exchange.FX_PROVIDER,
        fxObservedAt: Instant = observedAt,
    ) : this(
        pair = MarketPair.default(Symbol(symbol)),
        premiumRate = premiumRate,
        koreaPrice = koreaPrice,
        foreignPrice = foreignPrice,
        foreignPriceInKrw = foreignPriceInKrw,
        fxRate = fxRate,
        observedAt = observedAt,
        fxSource = fxSource,
        fxObservedAt = fxObservedAt,
    )
}
