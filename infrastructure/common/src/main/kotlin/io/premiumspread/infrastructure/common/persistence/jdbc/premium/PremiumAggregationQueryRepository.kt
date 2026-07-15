package io.premiumspread.infrastructure.common.persistence.jdbc.premium

import io.premiumspread.domain.market.MarketPair
import io.premiumspread.domain.premium.PremiumAggregationSnapshot
import io.premiumspread.infrastructure.common.config.AggregationInfrastructureProperties
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.Date
import java.sql.Timestamp
import java.time.Instant

@Repository
class PremiumAggregationQueryRepository(
    private val jdbcTemplate: JdbcTemplate,
    properties: AggregationInfrastructureProperties,
) {
    private val zoneId = properties.aggregationZone.zoneId

    fun findByInterval(
        pair: MarketPair,
        interval: String,
        from: Instant,
        to: Instant,
    ): List<PremiumAggregationSnapshot> {
        require(from < to) { "Aggregation range must satisfy from < to." }
        val (table, timeColumn) = when (interval) {
            "1m" -> "premium_minute" to "minute_at"
            "1h" -> "premium_hour" to "hour_at"
            "1d" -> "premium_day" to "day_at"
            else -> throw IllegalArgumentException("Invalid interval: $interval. Allowed: 1m, 1h, 1d")
        }

        val fromParam: Any = if (interval == "1d") {
            Date.valueOf(from.atZone(zoneId).toLocalDate())
        } else {
            Timestamp.from(from)
        }
        val toParam: Any = if (interval == "1d") {
            Date.valueOf(to.atZone(zoneId).toLocalDate())
        } else {
            Timestamp.from(to)
        }

        return jdbcTemplate.query(
            """
            SELECT symbol, $timeColumn, high, low, open, close, avg, count, fx_rate
            FROM $table
            WHERE korea_exchange = ?
              AND foreign_exchange = ?
              AND symbol = ?
              AND $timeColumn >= ? AND $timeColumn < ?
            ORDER BY $timeColumn ASC
            """.trimIndent(),
            { rs, _ ->
                PremiumAggregationSnapshot(
                    pair = pair,
                    high = rs.getBigDecimal("high"),
                    low = rs.getBigDecimal("low"),
                    open = rs.getBigDecimal("open"),
                    close = rs.getBigDecimal("close"),
                    avg = rs.getBigDecimal("avg"),
                    count = rs.getInt("count"),
                    observedAt = if (interval == "1d") {
                        rs.getDate(timeColumn).toLocalDate().atStartOfDay(zoneId).toInstant()
                    } else {
                        rs.getTimestamp(timeColumn).toInstant()
                    },
                    fxRate = rs.getBigDecimal("fx_rate"),
                )
            },
            pair.koreaExchange.name,
            pair.foreignExchange.name,
            pair.symbol.code,
            fromParam,
            toParam,
        )
    }
}
