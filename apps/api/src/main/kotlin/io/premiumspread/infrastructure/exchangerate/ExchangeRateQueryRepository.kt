package io.premiumspread.infrastructure.exchangerate

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.Instant

/**
 * 환율 조회용 Repository (Cache miss fallback용)
 */
@Repository
class ExchangeRateQueryRepository(
    private val jdbcTemplate: JdbcTemplate,
) {

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
