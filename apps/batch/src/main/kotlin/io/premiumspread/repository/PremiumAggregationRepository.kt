package io.premiumspread.repository

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

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
)

@Repository
class PremiumAggregationRepository(
    private val jdbcTemplate: JdbcTemplate,
) {

    /**
     * 분 집계 저장
     */
    fun saveMinute(symbol: String, minuteAt: LocalDateTime, agg: PremiumAggregation) {
        jdbcTemplate.update(
            """
            INSERT INTO premium_minute
            (symbol, minute_at, high, low, open, close, avg, count, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
            high = VALUES(high), low = VALUES(low), open = VALUES(open),
            close = VALUES(close), avg = VALUES(avg), count = VALUES(count)
            """.trimIndent(),
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
    fun saveHour(symbol: String, hourAt: LocalDateTime, agg: PremiumAggregation) {
        jdbcTemplate.update(
            """
            INSERT INTO premium_hour
            (symbol, hour_at, high, low, open, close, avg, count, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
            high = VALUES(high), low = VALUES(low), open = VALUES(open),
            close = VALUES(close), avg = VALUES(avg), count = VALUES(count)
            """.trimIndent(),
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
    fun saveDay(symbol: String, dayAt: LocalDate, agg: PremiumAggregation) {
        jdbcTemplate.update(
            """
            INSERT INTO premium_day
            (symbol, day_at, high, low, open, close, avg, count, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
            high = VALUES(high), low = VALUES(low), open = VALUES(open),
            close = VALUES(close), avg = VALUES(avg), count = VALUES(count)
            """.trimIndent(),
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
}
