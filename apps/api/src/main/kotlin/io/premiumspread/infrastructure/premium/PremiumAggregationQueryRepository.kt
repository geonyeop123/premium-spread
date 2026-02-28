package io.premiumspread.infrastructure.premium

import io.premiumspread.domain.premium.PremiumAggregationSnapshot
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

@Repository
class PremiumAggregationQueryRepository(
    private val jdbcTemplate: JdbcTemplate,
) {

    fun findByInterval(
        symbol: String,
        interval: String,
        from: Instant,
        to: Instant,
    ): List<PremiumAggregationSnapshot> {
        val (table, timeColumn) = when (interval) {
            "1m" -> "premium_minute" to "minute_at"
            "1h" -> "premium_hour" to "hour_at"
            "1d" -> "premium_day" to "day_at"
            else -> throw IllegalArgumentException("Invalid interval: $interval. Allowed: 1m, 1h, 1d")
        }

        // JVM 타임존에 무관하게 UTC 기준으로 변환 (Batch Docker 컨테이너가 UTC로 저장)
        val fromParam = Timestamp.valueOf(LocalDateTime.ofInstant(from, ZoneOffset.UTC))
        val toParam = Timestamp.valueOf(LocalDateTime.ofInstant(to, ZoneOffset.UTC))

        return jdbcTemplate.query(
            """
            SELECT symbol, $timeColumn, high, low, open, close, avg, count
            FROM $table
            WHERE symbol = ? AND $timeColumn >= ? AND $timeColumn < ?
            ORDER BY $timeColumn ASC
            """.trimIndent(),
            { rs, _ ->
                PremiumAggregationSnapshot(
                    symbol = rs.getString("symbol"),
                    high = rs.getBigDecimal("high"),
                    low = rs.getBigDecimal("low"),
                    open = rs.getBigDecimal("open"),
                    close = rs.getBigDecimal("close"),
                    avg = rs.getBigDecimal("avg"),
                    count = rs.getInt("count"),
                    observedAt = if (interval == "1d") {
                        rs.getDate(timeColumn).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant()
                    } else {
                        rs.getTimestamp(timeColumn).toLocalDateTime().toInstant(ZoneOffset.UTC)
                    },
                )
            },
            symbol.uppercase(),
            fromParam,
            toParam,
        )
    }
}
