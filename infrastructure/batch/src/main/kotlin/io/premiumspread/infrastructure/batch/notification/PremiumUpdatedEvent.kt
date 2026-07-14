package io.premiumspread.infrastructure.batch.notification

import java.math.BigDecimal

data class PremiumUpdatedEvent(
    val symbol: String,
    val premiumRate: BigDecimal,
)
