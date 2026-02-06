package io.premiumspread.domain.premium

import java.math.BigDecimal
import java.time.Instant

data class PremiumSnapshot(
    val symbol: String,
    val premiumRate: BigDecimal,
    val koreaPrice: BigDecimal,
    val foreignPrice: BigDecimal,
    val foreignPriceInKrw: BigDecimal,
    val fxRate: BigDecimal,
    val observedAt: Instant,
)
