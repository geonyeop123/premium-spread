package io.premiumspread.domain.exchangerate

import io.premiumspread.domain.ticker.Exchange
import java.math.BigDecimal
import java.time.Instant

data class ExchangeRateSnapshot(
    val baseCurrency: String,
    val quoteCurrency: String,
    val rate: BigDecimal,
    val observedAt: Instant,
    val source: Exchange = Exchange.FX_PROVIDER,
)
