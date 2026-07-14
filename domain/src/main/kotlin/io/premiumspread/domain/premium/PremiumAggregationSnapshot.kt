package io.premiumspread.domain.premium

import io.premiumspread.domain.market.MarketPair
import io.premiumspread.domain.ticker.Symbol
import java.math.BigDecimal
import java.time.Instant

data class PremiumAggregationSnapshot(
    val pair: MarketPair,
    val high: BigDecimal,
    val low: BigDecimal,
    val open: BigDecimal,
    val close: BigDecimal,
    val avg: BigDecimal,
    val count: Int,
    val observedAt: Instant,
    val fxRate: BigDecimal? = null,
    val updatedAt: Instant = observedAt,
) {
    val symbol: String
        get() = pair.symbol.code

    constructor(
        symbol: String,
        high: BigDecimal,
        low: BigDecimal,
        open: BigDecimal,
        close: BigDecimal,
        avg: BigDecimal,
        count: Int,
        observedAt: Instant,
        fxRate: BigDecimal? = null,
    ) : this(
        pair = MarketPair.default(Symbol(symbol)),
        high = high,
        low = low,
        open = open,
        close = close,
        avg = avg,
        count = count,
        observedAt = observedAt,
        fxRate = fxRate,
    )
}
