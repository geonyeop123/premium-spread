package io.premiumspread.infrastructure.ticker

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.Instant

/**
 * Ticker 집계 데이터 조회용 Repository (Cache miss fallback용)
 */
@Repository
class TickerAggregationQueryRepository(
    private val jdbcTemplate: JdbcTemplate,
) {

    /**
     * 최신 분 집계 데이터 조회
     */
    fun findLatestMinute(exchange: String, symbol: String): TickerAggregationSnapshot? {
        val results = jdbcTemplate.query(
            """
            SELECT exchange, symbol, minute_at, high, low, open, close, avg, count
            FROM ticker_minute
            WHERE exchange = ? AND symbol = ?
            ORDER BY minute_at DESC
            LIMIT 1
            """.trimIndent(),
            { rs, _ ->
                TickerAggregationSnapshot(
                    exchange = rs.getString("exchange"),
                    symbol = rs.getString("symbol"),
                    high = rs.getBigDecimal("high"),
                    low = rs.getBigDecimal("low"),
                    open = rs.getBigDecimal("open"),
                    close = rs.getBigDecimal("close"),
                    avg = rs.getBigDecimal("avg"),
                    count = rs.getInt("count"),
                    observedAt = rs.getTimestamp("minute_at").toInstant(),
                )
            },
            exchange.uppercase(),
            symbol.uppercase(),
        )
        return results.firstOrNull()
    }
}

data class TickerAggregationSnapshot(
    val exchange: String,
    val symbol: String,
    val high: BigDecimal,
    val low: BigDecimal,
    val open: BigDecimal,
    val close: BigDecimal,
    val avg: BigDecimal,
    val count: Int,
    val observedAt: Instant,
)
