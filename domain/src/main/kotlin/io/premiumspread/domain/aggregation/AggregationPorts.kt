package io.premiumspread.domain.aggregation

import io.premiumspread.domain.market.MarketPair
import io.premiumspread.domain.premium.PremiumAggregationSnapshot
import io.premiumspread.domain.ticker.Exchange
import io.premiumspread.domain.ticker.TickerSnapshot
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

enum class AggregationUnit(val duration: Duration) {
    MINUTE(Duration.ofMinutes(1)),
    HOUR(Duration.ofHours(1)),
    DAY(Duration.ofDays(1)),
}

data class AggregationWindow(
    val from: Instant,
    val to: Instant,
    val zone: AggregationZone,
) {
    init {
        require(from < to) { "Aggregation window must have from < to." }
    }
}

class AggregationZone private constructor(val zoneId: ZoneId) {
    override fun equals(other: Any?): Boolean = other is AggregationZone && zoneId == other.zoneId

    override fun hashCode(): Int = zoneId.hashCode()

    override fun toString(): String = zoneId.id

    companion object {
        val DEFAULT: AggregationZone = AggregationZone(ZoneId.of("Asia/Seoul"))

        fun of(value: String): AggregationZone = AggregationZone(ZoneId.of(value))
    }
}

interface TickerAggregationReadPort {
    fun read(exchange: Exchange, window: AggregationWindow, unit: AggregationUnit): List<TickerSnapshot>
}

interface TickerAggregationWritePort {
    fun save(exchange: Exchange, snapshot: TickerSnapshot, window: AggregationWindow, unit: AggregationUnit)
}

interface PremiumAggregationReadPort {
    fun read(pair: MarketPair, window: AggregationWindow, unit: AggregationUnit): List<PremiumAggregationSnapshot>
}

interface PremiumAggregationWritePort {
    fun save(snapshot: PremiumAggregationSnapshot, window: AggregationWindow, unit: AggregationUnit)
}
