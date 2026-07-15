package io.premiumspread.infrastructure.common.persistence.jdbc.exchangerate

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.sql.Timestamp

@Repository
class JdbcExchangeRateWriteRepository(private val jdbcTemplate: JdbcTemplate, private val clock: Clock) {

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
            Timestamp.from(clock.instant()),
        )
    }
}
