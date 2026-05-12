package io.premiumspread.application.notification

import java.math.BigDecimal

data class PremiumUpdatedEvent(
    val symbol: String,
    val premiumRate: BigDecimal,
)
