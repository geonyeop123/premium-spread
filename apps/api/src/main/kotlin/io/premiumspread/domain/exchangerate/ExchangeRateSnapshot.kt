package io.premiumspread.domain.exchangerate

import java.math.BigDecimal
import java.time.Instant

data class ExchangeRateSnapshot(
    val baseCurrency: String,
    val quoteCurrency: String,
    val rate: BigDecimal,
    val observedAt: Instant,
)
