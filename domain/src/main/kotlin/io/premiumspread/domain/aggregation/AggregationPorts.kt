package io.premiumspread.domain.aggregation

import io.premiumspread.domain.market.MarketPair
import io.premiumspread.domain.premium.PremiumAggregationSnapshot
import io.premiumspread.domain.ticker.Exchange
import io.premiumspread.domain.ticker.Quote
import io.premiumspread.domain.ticker.TickerSnapshot
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

enum class AggregationUnit(val duration: Duration) {
    MINUTE(Duration.ofMinutes(1)),
    HOUR(Duration.ofHours(1)),
    DAY(Duration.ofDays(1)),
}

data class AggregationWindow(val from: Instant, val to: Instant, val zone: AggregationZone) {
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

/** ticker OHLC 집계의 domain DTO. infrastructure 전용 JDBC/Redis DTO를 application에 노출하지 않는다. */
data class TickerAggregationSnapshot(
    val exchange: Exchange,
    val quote: Quote,
    val high: BigDecimal,
    val low: BigDecimal,
    val open: BigDecimal,
    val close: BigDecimal,
    val average: BigDecimal,
    val count: Int,
    val observedAt: Instant,
)

/**
 * raw seconds 또는 직전 집계 단위에서 ticker aggregate를 읽고, DB/cache에 함께 저장하는 경계다.
 * [sourceUnit]이 null이면 raw seconds를 의미한다.
 */
interface TickerAggregatePort {
    fun aggregate(
        exchange: Exchange,
        quote: Quote,
        sourceUnit: AggregationUnit?,
        window: AggregationWindow,
    ): TickerAggregationSnapshot?

    fun save(unit: AggregationUnit, window: AggregationWindow, snapshot: TickerAggregationSnapshot)
}

enum class PremiumSummaryPeriod(val duration: Duration, val sourceUnit: AggregationUnit?) {
    ONE_MINUTE(Duration.ofMinutes(1), null),
    TEN_MINUTES(Duration.ofMinutes(10), null),
    ONE_HOUR(Duration.ofHours(1), AggregationUnit.MINUTE),
    ONE_DAY(Duration.ofDays(1), AggregationUnit.HOUR),
}

/**
 * premium aggregate와 summary의 cache/JDBC 조합을 감춘다.
 * [sourceUnit]이 null이면 raw seconds에서 집계한다.
 */
interface PremiumAggregatePort {
    fun aggregate(
        pair: MarketPair,
        sourceUnit: AggregationUnit?,
        window: AggregationWindow,
    ): PremiumAggregationSnapshot?

    fun save(unit: AggregationUnit, window: AggregationWindow, snapshot: PremiumAggregationSnapshot)

    fun calculateSummary(
        pair: MarketPair,
        period: PremiumSummaryPeriod,
        window: AggregationWindow,
    ): PremiumAggregationSnapshot?

    fun saveSummary(period: PremiumSummaryPeriod, snapshot: PremiumAggregationSnapshot)
}
