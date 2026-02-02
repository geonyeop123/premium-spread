package io.premiumspread.repository

import io.premiumspread.cache.PremiumCacheData
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.Timestamp

@Repository
class PremiumSnapshotRepository(
    private val jdbcTemplate: JdbcTemplate,
) {

    fun save(premium: PremiumCacheData) {
        jdbcTemplate.update(
            """
            INSERT INTO premium_snapshot
            (symbol, premium_rate, korea_price, foreign_price, foreign_price_krw, fx_rate, observed_at, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            premium.symbol.uppercase(),
            premium.premiumRate,
            premium.koreaPrice,
            premium.foreignPrice,
            premium.foreignPriceInKrw,
            premium.fxRate,
            Timestamp.from(premium.observedAt),
            Timestamp.from(java.time.Instant.now()),
        )
    }
}
