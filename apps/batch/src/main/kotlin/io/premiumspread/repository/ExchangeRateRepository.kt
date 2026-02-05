package io.premiumspread.repository

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Instant

@Repository
class ExchangeRateRepository(
    private val jdbcTemplate: JdbcTemplate,
) {

    /**
     * 환율 스냅샷 저장 (30분 단위)
     */
    fun save(baseCurrency: String, quoteCurrency: String, rate: BigDecimal, observedAt: Instant) {
        jdbcTemplate.update(
            """
            INSERT INTO exchange_rate
            (base_currency, quote_currency, rate, observed_at, created_at)
            VALUES (?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
            rate = VALUES(rate)
            """.trimIndent(),
            baseCurrency.uppercase(),
            quoteCurrency.uppercase(),
            rate,
            Timestamp.from(observedAt),
            Timestamp.from(Instant.now()),
        )
    }

    /**
     * 최신 환율 조회
     */
    fun findLatest(baseCurrency: String, quoteCurrency: String): ExchangeRateSnapshot? {
        val results = jdbcTemplate.query(
            """
            SELECT base_currency, quote_currency, rate, observed_at
            FROM exchange_rate
            WHERE base_currency = ? AND quote_currency = ?
            ORDER BY observed_at DESC
            LIMIT 1
            """.trimIndent(),
            { rs, _ ->
                ExchangeRateSnapshot(
                    baseCurrency = rs.getString("base_currency"),
                    quoteCurrency = rs.getString("quote_currency"),
                    rate = rs.getBigDecimal("rate"),
                    observedAt = rs.getTimestamp("observed_at").toInstant(),
                )
            },
            baseCurrency.uppercase(),
            quoteCurrency.uppercase(),
        )
        return results.firstOrNull()
    }
}

data class ExchangeRateSnapshot(
    val baseCurrency: String,
    val quoteCurrency: String,
    val rate: BigDecimal,
    val observedAt: Instant,
)
