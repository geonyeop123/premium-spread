package io.premiumspread.repository

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Ticker 집계 데이터
 */
data class TickerAggregation(
    val exchange: String,
    val symbol: String,
    val high: BigDecimal,
    val low: BigDecimal,
    val open: BigDecimal,
    val close: BigDecimal,
    val avg: BigDecimal,
    val count: Int,
)

@Repository
class TickerAggregationRepository(
    private val jdbcTemplate: JdbcTemplate,
) {

    /**
     * 분 집계 저장
     */
    fun saveMinute(exchange: String, symbol: String, minuteAt: LocalDateTime, agg: TickerAggregation) {
        jdbcTemplate.update(
            """
            INSERT INTO ticker_minute
            (exchange, symbol, minute_at, high, low, open, close, avg, count, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
            high = VALUES(high), low = VALUES(low), open = VALUES(open),
            close = VALUES(close), avg = VALUES(avg), count = VALUES(count)
            """.trimIndent(),
            exchange.uppercase(),
            symbol.uppercase(),
            Timestamp.valueOf(minuteAt),
            agg.high,
            agg.low,
            agg.open,
            agg.close,
            agg.avg,
            agg.count,
            Timestamp.from(Instant.now()),
        )
    }

    /**
     * 시간 집계 저장
     */
    fun saveHour(exchange: String, symbol: String, hourAt: LocalDateTime, agg: TickerAggregation) {
        jdbcTemplate.update(
            """
            INSERT INTO ticker_hour
            (exchange, symbol, hour_at, high, low, open, close, avg, count, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
            high = VALUES(high), low = VALUES(low), open = VALUES(open),
            close = VALUES(close), avg = VALUES(avg), count = VALUES(count)
            """.trimIndent(),
            exchange.uppercase(),
            symbol.uppercase(),
            Timestamp.valueOf(hourAt),
            agg.high,
            agg.low,
            agg.open,
            agg.close,
            agg.avg,
            agg.count,
            Timestamp.from(Instant.now()),
        )
    }

    /**
     * 일 집계 저장
     */
    fun saveDay(exchange: String, symbol: String, dayAt: LocalDate, agg: TickerAggregation) {
        jdbcTemplate.update(
            """
            INSERT INTO ticker_day
            (exchange, symbol, day_at, high, low, open, close, avg, count, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
            high = VALUES(high), low = VALUES(low), open = VALUES(open),
            close = VALUES(close), avg = VALUES(avg), count = VALUES(count)
            """.trimIndent(),
            exchange.uppercase(),
            symbol.uppercase(),
            java.sql.Date.valueOf(dayAt),
            agg.high,
            agg.low,
            agg.open,
            agg.close,
            agg.avg,
            agg.count,
            Timestamp.from(Instant.now()),
        )
    }

    fun findLatestMinute(exchange: String, symbol: String): TickerAggregation? {
        val results = jdbcTemplate.query(
            "SELECT * FROM ticker_minute WHERE exchange = ? AND symbol = ? ORDER BY minute_at DESC LIMIT 1",
            { rs, _ -> mapToAggregation(rs) },
            exchange.uppercase(),
            symbol.uppercase(),
        )
        return results.firstOrNull()
    }

    fun findLatestHour(exchange: String, symbol: String): TickerAggregation? {
        val results = jdbcTemplate.query(
            "SELECT * FROM ticker_hour WHERE exchange = ? AND symbol = ? ORDER BY hour_at DESC LIMIT 1",
            { rs, _ -> mapToAggregation(rs) },
            exchange.uppercase(),
            symbol.uppercase(),
        )
        return results.firstOrNull()
    }

    fun findLatestDay(exchange: String, symbol: String): TickerAggregation? {
        val results = jdbcTemplate.query(
            "SELECT * FROM ticker_day WHERE exchange = ? AND symbol = ? ORDER BY day_at DESC LIMIT 1",
            { rs, _ -> mapToAggregation(rs) },
            exchange.uppercase(),
            symbol.uppercase(),
        )
        return results.firstOrNull()
    }

    private fun mapToAggregation(rs: java.sql.ResultSet) = TickerAggregation(
        exchange = rs.getString("exchange"),
        symbol = rs.getString("symbol"),
        high = rs.getBigDecimal("high"),
        low = rs.getBigDecimal("low"),
        open = rs.getBigDecimal("open"),
        close = rs.getBigDecimal("close"),
        avg = rs.getBigDecimal("avg"),
        count = rs.getInt("count"),
    )
}
