package io.premiumspread.application.ticker

import io.premiumspread.domain.ticker.Premium
import java.math.BigDecimal
import java.time.Instant

data class PremiumCreateCriteria(
    val symbol: String,
)

data class PremiumResult(
    val id: Long,
    val symbol: String,
    val koreaTickerId: Long,
    val foreignTickerId: Long,
    val fxTickerId: Long,
    val premiumRate: BigDecimal,
    val observedAt: Instant,
) {
    companion object {
        fun from(premium: Premium): PremiumResult = PremiumResult(
            id = premium.id,
            symbol = premium.symbol.code,
            koreaTickerId = premium.koreaTickerId,
            foreignTickerId = premium.foreignTickerId,
            fxTickerId = premium.fxTickerId,
            premiumRate = premium.premiumRate,
            observedAt = premium.observedAt,
        )
    }
}
