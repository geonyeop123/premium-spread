package io.premiumspread.application.premium

import io.premiumspread.domain.premium.Premium
import java.math.BigDecimal
import java.time.Instant

class PremiumCriteria private constructor() {
    data class Create(
        val symbol: String,
    )
}

class PremiumResult private constructor() {
    data class Detail(
        val id: Long,
        val symbol: String,
        val koreaTickerId: Long,
        val foreignTickerId: Long,
        val fxTickerId: Long,
        val premiumRate: BigDecimal,
        val observedAt: Instant,
    ) {
        companion object {
            fun from(premium: Premium): Detail = Detail(
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
}
