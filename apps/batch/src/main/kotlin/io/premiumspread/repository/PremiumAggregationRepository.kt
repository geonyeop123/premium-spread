package io.premiumspread.repository

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.LocalDate

/**
 * 프리미엄 집계 데이터
 */
data class PremiumAggregation(
    val symbol: String,
    val high: BigDecimal,
    val low: BigDecimal,
    val open: BigDecimal,
    val close: BigDecimal,
    val avg: BigDecimal,
    val count: Int,
    val fxRate: BigDecimal? = null,
)

@Repository
class PremiumAggregationRepository(
    private val jdbcTemplate: JdbcTemplate,
    private val clock: Clock,
) {

    /**
     * 분 집계 저장
     */
    fun saveMinute(symbol: String, minuteAt: Instant, agg: PremiumAggregation) {
        jdbcTemplate.update(
            """
            INSERT INTO premium_minute
            (symbol, minute_at, high, low, open, close, avg, count, fx_rate, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
            high = VALUES(high), low = VALUES(low), open = VALUES(open),
            close = VALUES(close), avg = VALUES(avg), count = VALUES(count),
            fx_rate = VALUES(fx_rate)
            """.trimIndent(),
            symbol.uppercase(),
            Timestamp.from(minuteAt),
            agg.high,
            agg.low,
            agg.open,
            agg.close,
            agg.avg,
            agg.count,
            agg.fxRate,
            Timestamp.from(clock.instant()),
        )
    }

    /**
     * 시간 집계 저장
     */
    fun saveHour(symbol: String, hourAt: Instant, agg: PremiumAggregation) {
        jdbcTemplate.update(
            """
            INSERT INTO premium_hour
            (symbol, hour_at, high, low, open, close, avg, count, fx_rate, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
            high = VALUES(high), low = VALUES(low), open = VALUES(open),
            close = VALUES(close), avg = VALUES(avg), count = VALUES(count),
            fx_rate = VALUES(fx_rate)
            """.trimIndent(),
            symbol.uppercase(),
            Timestamp.from(hourAt),
            agg.high,
            agg.low,
            agg.open,
            agg.close,
            agg.avg,
            agg.count,
            agg.fxRate,
            Timestamp.from(clock.instant()),
        )
    }

    /**
     * 일 집계 저장
     */
    fun saveDay(symbol: String, dayAt: LocalDate, agg: PremiumAggregation) {
        jdbcTemplate.update(
            """
            INSERT INTO premium_day
            (symbol, day_at, high, low, open, close, avg, count, fx_rate, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
            high = VALUES(high), low = VALUES(low), open = VALUES(open),
            close = VALUES(close), avg = VALUES(avg), count = VALUES(count),
            fx_rate = VALUES(fx_rate)
            """.trimIndent(),
            symbol.uppercase(),
            java.sql.Date.valueOf(dayAt),
            agg.high,
            agg.low,
            agg.open,
            agg.close,
            agg.avg,
            agg.count,
            agg.fxRate,
            Timestamp.from(clock.instant()),
        )
    }

    fun findLatestMinute(symbol: String): PremiumAggregation? {
        val results = jdbcTemplate.query(
            "SELECT * FROM premium_minute WHERE symbol = ? ORDER BY minute_at DESC LIMIT 1",
            { rs, _ -> mapToAggregation(rs) },
            symbol.uppercase(),
        )
        return results.firstOrNull()
    }

    fun findLatestHour(symbol: String): PremiumAggregation? {
        val results = jdbcTemplate.query(
            "SELECT * FROM premium_hour WHERE symbol = ? ORDER BY hour_at DESC LIMIT 1",
            { rs, _ -> mapToAggregation(rs) },
            symbol.uppercase(),
        )
        return results.firstOrNull()
    }

    fun findLatestDay(symbol: String): PremiumAggregation? {
        val results = jdbcTemplate.query(
            "SELECT * FROM premium_day WHERE symbol = ? ORDER BY day_at DESC LIMIT 1",
            { rs, _ -> mapToAggregation(rs) },
            symbol.uppercase(),
        )
        return results.firstOrNull()
    }

    private fun mapToAggregation(rs: java.sql.ResultSet) = PremiumAggregation(
        symbol = rs.getString("symbol"),
        high = rs.getBigDecimal("high"),
        low = rs.getBigDecimal("low"),
        open = rs.getBigDecimal("open"),
        close = rs.getBigDecimal("close"),
        avg = rs.getBigDecimal("avg"),
        count = rs.getInt("count"),
        fxRate = rs.getBigDecimal("fx_rate"),
    )
}
