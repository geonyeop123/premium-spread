package io.premiumspread.infrastructure.common.persistence.jdbc.accountpnl

import io.premiumspread.domain.accountpnl.AccountRealizedPnlStore
import io.premiumspread.domain.accountpnl.AppliedFx
import io.premiumspread.domain.accountpnl.ForeignLeg
import io.premiumspread.domain.accountpnl.ForeignPerpPositionSource
import io.premiumspread.domain.accountpnl.KoreaLeg
import io.premiumspread.domain.accountpnl.PositionLegs
import io.premiumspread.domain.accountpnl.SettlementOutcome
import io.premiumspread.domain.accountpnl.SettlementStatus
import io.premiumspread.domain.accountpnl.SettlementTarget
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant

/**
 * 정리한 포지션의 확정 손익 정산 상태 (#130, design D2·D3·D5~D7).
 *
 * - [findTarget]: 정리 사건(REDUCED·ADJUSTED·CLOSED)이 `settled_seq` 를 앞선 건 가운데 가장 오래된 건 1개와
 *   그 건의 목표 사건(조건을 만족하는 가장 큰 seq)을 고른다. backoff·90일 한도는 SQL 이 거른다.
 * - [findFxAt]: 사건 시각의 관측 행, 없으면 `[at − tolerance, at]` 의 최신 관측 행.
 * - [record]: `settled_seq` 가 뒤로 가지 않는 조건부 UPDATE 한 문장이다. 미확정 기록은 합계·환율(settled_seq 의 값)을
 *   건드리지 않고, 쪽 값은 그 쪽이 CONFIRMED 일 때만 바꾼다.
 * - 금액·수량은 로그·예외 메시지에 넣지 않는다.
 */
@Repository
class JdbcAccountRealizedPnlStore(private val jdbcTemplate: JdbcTemplate) : AccountRealizedPnlStore {

    override fun findTarget(now: Instant, settleDelay: Duration, maxAge: Duration, firstLookback: Duration): SettlementTarget? {
        val candidate = jdbcTemplate.query(
            CANDIDATE_SQL,
            { rs, _ ->
                Candidate(
                    episodeId = rs.getLong("id"),
                    targetSeq = rs.getInt("target_seq"),
                    lowerExclusive = rs.getTimestamp("lower_exclusive").toInstant(),
                    attempts = rs.getInt("attempts"),
                )
            },
            firstLookback.toMicros(),
            now.minus(settleDelay).toSqlTimestamp(),
            now.toSqlTimestamp(),
            now.minus(maxAge).toSqlTimestamp(),
        ).singleOrNull() ?: return null

        return jdbcTemplate.query(
            TARGET_EVENT_SQL,
            { rs, _ -> rs.toTarget(candidate) },
            candidate.episodeId,
            candidate.targetSeq,
        ).single()
    }

    /** `[at − tolerance, at]` 의 최신 행이다. `observed_at = at` 행이 있으면 그것이 최신이므로 먼저 선택된다. */
    override fun findFxAt(at: Instant, tolerance: Duration): AppliedFx? =
        jdbcTemplate.query(
            """
            SELECT fx_rate, observed_at
            FROM account_pnl_observation
            WHERE observed_at >= ? AND observed_at <= ?
            ORDER BY observed_at DESC, id DESC
            LIMIT 1
            """.trimIndent(),
            { rs, _ -> AppliedFx(rs.getBigDecimal("fx_rate"), rs.getTimestamp("observed_at").toInstant()) },
            at.minus(tolerance).toSqlTimestamp(),
            at.toSqlTimestamp(),
        ).singleOrNull()

    override fun record(outcome: SettlementOutcome, nextAttemptAt: Instant?): Boolean {
        val target = outcome.target
        val affected = if (outcome.settled) {
            jdbcTemplate.update(
                RECORD_SETTLED_SQL,
                outcome.koreaStatus.name,
                outcome.foreignStatus.name,
                outcome.korea?.realizedKrw,
                outcome.foreign?.realizedPnlUsdt,
                outcome.foreign?.commissionUsdt,
                outcome.foreign?.fundingUsdt,
                outcome.fx?.rate,
                outcome.fx?.observedAt?.toSqlTimestamp(),
                outcome.totalKrw,
                target.seq,
                target.seq,
                outcome.decidedAt.toSqlTimestamp(),
                target.episodeId,
                target.seq,
            )
        } else {
            // 합계·환율은 settled_seq 의 것이다 — 미확정 기록은 건드리지 않는다. 쪽 값은 그 쪽이 CONFIRMED 일 때만 바꾼다.
            val korea = outcome.korea.takeIf { outcome.koreaStatus == SettlementStatus.CONFIRMED }
            val foreign = outcome.foreign.takeIf { outcome.foreignStatus == SettlementStatus.CONFIRMED }
            jdbcTemplate.update(
                RECORD_UNSETTLED_SQL,
                outcome.koreaStatus.name,
                outcome.foreignStatus.name,
                korea?.realizedKrw,
                foreign?.realizedPnlUsdt,
                foreign?.commissionUsdt,
                foreign?.fundingUsdt,
                target.seq,
                nextAttemptAt?.toSqlTimestamp(),
                outcome.failure?.name,
                target.episodeId,
                target.seq,
            )
        }
        return affected == 1
    }

    private fun ResultSet.toTarget(candidate: Candidate): SettlementTarget = SettlementTarget(
        episodeId = candidate.episodeId,
        seq = candidate.targetSeq,
        occurredAt = getTimestamp("occurred_at").toInstant(),
        legs = PositionLegs(
            korea = KoreaLeg.of(decimal("korea_quantity"), decimal("korea_average_price")),
            foreign = ForeignLeg.of(decimal("foreign_signed_quantity"), decimal("foreign_entry_price")),
        ),
        source = ForeignPerpPositionSource.valueOf(getString("position_source")),
        lowerExclusive = candidate.lowerExclusive,
        attempts = candidate.attempts,
    )

    private fun ResultSet.decimal(column: String): BigDecimal = BigDecimal(getString(column))

    private fun Instant.toSqlTimestamp(): Timestamp = Timestamp.from(this)

    private fun Duration.toMicros(): Long = toNanos() / NANOS_PER_MICRO

    private data class Candidate(val episodeId: Long, val targetSeq: Int, val lowerExclusive: Instant, val attempts: Int)

    private companion object {
        const val NANOS_PER_MICRO = 1_000L

        /**
         * 파라미터 순서: first lookback(µs), 사건 기준 시각(now − delay), now(backoff), 하한 기준 시각(now − maxAge).
         * backoff 는 목표 seq 가 마지막 시도 목표(`settlement_target_seq`) 이하일 때만 적용한다 — 새 정리 사건이면 무시한다.
         */
        val CANDIDATE_SQL = """
            SELECT c.id, c.target_seq, c.lower_exclusive, c.attempts
            FROM (
                SELECT ep.id,
                       ep.opened_at,
                       t.target_seq,
                       COALESCE(ep.settlement_attempts, 0) AS attempts,
                       ep.settlement_next_at,
                       ep.settlement_target_seq,
                       COALESCE(
                           (SELECT MAX(prev.closed_at) FROM account_position_episode prev
                            WHERE prev.id <> ep.id AND prev.closed_at < ep.opened_at),
                           ep.opened_at - INTERVAL ? MICROSECOND
                       ) AS lower_exclusive
                FROM account_position_episode ep
                JOIN (
                    SELECT e.episode_id, MAX(e.seq) AS target_seq
                    FROM account_position_event e
                    JOIN account_position_episode x ON x.id = e.episode_id
                    WHERE e.event_type IN ('REDUCED', 'ADJUSTED', 'CLOSED')
                      AND e.seq > COALESCE(x.settled_seq, 0)
                      AND e.occurred_at <= ?
                    GROUP BY e.episode_id
                ) t ON t.episode_id = ep.id
            ) c
            WHERE NOT (c.settlement_next_at IS NOT NULL AND c.settlement_next_at > ?
                       AND c.target_seq <= COALESCE(c.settlement_target_seq, 0))
              AND c.lower_exclusive > ?
            ORDER BY c.opened_at, c.id
            LIMIT 1
        """.trimIndent()

        val TARGET_EVENT_SQL = """
            SELECT occurred_at, korea_quantity, korea_average_price, foreign_signed_quantity, foreign_entry_price,
                   position_source
            FROM account_position_event
            WHERE episode_id = ? AND seq = ?
        """.trimIndent()

        const val MONOTONIC_WHERE = """
            WHERE id = ?
              AND (settled_seq IS NULL OR settled_seq < ?)"""

        val RECORD_SETTLED_SQL = """
            UPDATE account_position_episode
            SET korea_settlement = ?,
                foreign_settlement = ?,
                korea_realized_krw = ?,
                foreign_realized_pnl_usdt = ?,
                foreign_commission_usdt = ?,
                foreign_funding_usdt = ?,
                fx_rate = ?,
                fx_observed_at = ?,
                total_realized_krw = ?,
                settlement_target_seq = ?,
                settled_seq = ?,
                settled_at = ?,
                settlement_attempts = NULL,
                settlement_next_at = NULL,
                settlement_failure = NULL
            $MONOTONIC_WHERE
        """.trimIndent()

        val RECORD_UNSETTLED_SQL = """
            UPDATE account_position_episode
            SET korea_settlement = ?,
                foreign_settlement = ?,
                korea_realized_krw = COALESCE(?, korea_realized_krw),
                foreign_realized_pnl_usdt = COALESCE(?, foreign_realized_pnl_usdt),
                foreign_commission_usdt = COALESCE(?, foreign_commission_usdt),
                foreign_funding_usdt = COALESCE(?, foreign_funding_usdt),
                settlement_target_seq = ?,
                settlement_attempts = COALESCE(settlement_attempts, 0) + 1,
                settlement_next_at = ?,
                settlement_failure = ?
            $MONOTONIC_WHERE
        """.trimIndent()
    }
}
