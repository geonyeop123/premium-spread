package io.premiumspread.infrastructure.common.persistence.jdbc.accountpnl

import io.premiumspread.domain.accountpnl.AccountPnlLatest
import io.premiumspread.domain.accountpnl.AccountPnlLatestStore
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Clock

/** 실계정 최신 순수익률 단일 행(account_pnl_latest, id = 1) upsert (owner-market-alerts design §6.2). 금액은 로그에 남기지 않는다. */
@Repository
class JdbcAccountPnlLatestStore(private val jdbcTemplate: JdbcTemplate, private val clock: Clock) : AccountPnlLatestStore {

    override fun record(latest: AccountPnlLatest) {
        val calculatedAt = Timestamp.from(latest.calculatedAt)
        val updatedAt = Timestamp.from(clock.instant())
        jdbcTemplate.update(
            "INSERT INTO account_pnl_latest (id, has_position, net_percent, net_krw, calculated_at, updated_at) " +
                "VALUES (1, ?, ?, ?, ?, ?) " +
                "ON DUPLICATE KEY UPDATE has_position = ?, net_percent = ?, net_krw = ?, calculated_at = ?, updated_at = ?",
            latest.position,
            latest.netPercent,
            latest.netKrw,
            calculatedAt,
            updatedAt,
            latest.position,
            latest.netPercent,
            latest.netKrw,
            calculatedAt,
            updatedAt,
        )
    }

    override fun find(): AccountPnlLatest? =
        jdbcTemplate.query(
            "SELECT has_position, net_percent, net_krw, calculated_at FROM account_pnl_latest WHERE id = 1",
        ) { rs, _ ->
            AccountPnlLatest(
                position = rs.getBoolean("has_position"),
                netPercent = rs.getBigDecimal("net_percent"),
                netKrw = rs.getBigDecimal("net_krw"),
                calculatedAt = rs.getTimestamp("calculated_at").toInstant(),
            )
        }.firstOrNull()
}
