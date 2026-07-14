package io.premiumspread.repository

import io.premiumspread.cache.PremiumCacheData
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Clock

@Repository
class PremiumSnapshotRepository(
    private val jdbcTemplate: JdbcTemplate,
    private val clock: Clock,
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
            Timestamp.from(clock.instant()),
        )
    }
}
