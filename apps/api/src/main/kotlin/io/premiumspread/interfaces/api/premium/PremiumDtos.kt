package io.premiumspread.interfaces.api.premium

import io.premiumspread.application.premium.PremiumCacheResult
import io.premiumspread.application.premium.PremiumResult
import java.math.BigDecimal
import java.time.Instant

class PremiumResponse private constructor() {
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
            fun from(result: PremiumResult.Detail): Detail = Detail(
                id = result.id,
                symbol = result.symbol,
                koreaTickerId = result.koreaTickerId,
                foreignTickerId = result.foreignTickerId,
                fxTickerId = result.fxTickerId,
                premiumRate = result.premiumRate,
                observedAt = result.observedAt,
            )
        }
    }

    data class Current(
        val symbol: String,
        val premiumRate: BigDecimal,
        val koreaPrice: BigDecimal,
        val foreignPrice: BigDecimal,
        val foreignPriceInKrw: BigDecimal,
        val fxRate: BigDecimal,
        val observedAt: Instant,
        val source: String,
    ) {
        companion object {
            fun from(result: PremiumCacheResult): Current = Current(
                symbol = result.symbol,
                premiumRate = result.premiumRate,
                koreaPrice = result.koreaPrice,
                foreignPrice = result.foreignPrice,
                foreignPriceInKrw = result.foreignPriceInKrw,
                fxRate = result.fxRate,
                observedAt = result.observedAt,
                source = result.source.name,
            )
        }
    }
}
