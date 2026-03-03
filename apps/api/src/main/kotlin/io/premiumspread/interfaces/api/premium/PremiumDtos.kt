package io.premiumspread.interfaces.api.premium

import io.premiumspread.application.premium.PremiumResult
import io.premiumspread.domain.premium.PremiumSnapshot
import java.math.BigDecimal
import java.time.Instant

class PremiumResponse private constructor() {
    data class AggregationPage(
        val data: List<Aggregation>,
        val hasMore: Boolean,
    )

    data class Aggregation(
        val symbol: String,
        val high: BigDecimal,
        val low: BigDecimal,
        val open: BigDecimal,
        val close: BigDecimal,
        val avg: BigDecimal,
        val count: Int,
        val observedAt: Instant,
    ) {
        companion object {
            fun from(result: PremiumResult.Aggregation): Aggregation = Aggregation(
                symbol = result.symbol,
                high = result.high,
                low = result.low,
                open = result.open,
                close = result.close,
                avg = result.avg,
                count = result.count,
                observedAt = result.observedAt,
            )
        }
    }

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
    ) {
        companion object {
            fun from(snapshot: PremiumSnapshot): Current = Current(
                symbol = snapshot.symbol,
                premiumRate = snapshot.premiumRate,
                koreaPrice = snapshot.koreaPrice,
                foreignPrice = snapshot.foreignPrice,
                foreignPriceInKrw = snapshot.foreignPriceInKrw,
                fxRate = snapshot.fxRate,
                observedAt = snapshot.observedAt,
            )
        }
    }
}
