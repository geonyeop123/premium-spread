package io.premiumspread.application.premium

import io.premiumspread.domain.premium.Premium
import io.premiumspread.domain.premium.PremiumAggregationSnapshot
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

    data class Aggregation(
        val symbol: String,
        val high: BigDecimal,
        val low: BigDecimal,
        val open: BigDecimal,
        val close: BigDecimal,
        val avg: BigDecimal,
        val count: Int,
        val observedAt: Instant,
        val fxRate: BigDecimal?,
    ) {
        companion object {
            fun from(snapshot: PremiumAggregationSnapshot): Aggregation = Aggregation(
                symbol = snapshot.symbol,
                high = snapshot.high,
                low = snapshot.low,
                open = snapshot.open,
                close = snapshot.close,
                avg = snapshot.avg,
                count = snapshot.count,
                observedAt = snapshot.observedAt,
                fxRate = snapshot.fxRate,
            )
        }
    }
}
