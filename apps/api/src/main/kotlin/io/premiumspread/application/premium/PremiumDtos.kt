package io.premiumspread.application.premium

import java.math.BigDecimal
import java.time.Instant

class PremiumCriteria private constructor() {
    data class Create(val symbol: String)
    data class FindCurrent(val symbol: String)
    data class FindHistory(val symbol: String, val from: Instant, val to: Instant)
    data class FindAggregation(val symbol: String, val interval: String, val from: Instant, val to: Instant)
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
    )

    data class Details(val items: List<Detail>)

    data class Current(
        val symbol: String,
        val premiumRate: BigDecimal,
        val koreaPrice: BigDecimal,
        val foreignPrice: BigDecimal,
        val foreignPriceInKrw: BigDecimal,
        val fxRate: BigDecimal,
        val observedAt: Instant,
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
        val fxRate: BigDecimal?,
    )

    data class AggregationPage(
        val data: List<Aggregation>,
        val hasMore: Boolean,
    )
}
