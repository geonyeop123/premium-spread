package io.premiumspread.infrastructure.common.persistence.jdbc.accountpnl

import io.premiumspread.domain.accountpnl.AccountPositionHistoryStore
import io.premiumspread.domain.accountpnl.AccountReferencePositionReadPort
import io.premiumspread.domain.accountpnl.ForeignLeg
import io.premiumspread.domain.accountpnl.KoreaLeg
import io.premiumspread.domain.accountpnl.OpenEpisode
import io.premiumspread.domain.accountpnl.PositionEvent
import io.premiumspread.domain.accountpnl.PositionEventType
import io.premiumspread.domain.accountpnl.PositionEpisodePolicy
import io.premiumspread.domain.accountpnl.PositionHistoryRound
import io.premiumspread.domain.accountpnl.PositionLegs
import io.premiumspread.domain.accountpnl.PositionObservation
import io.premiumspread.domain.accountpnl.ReferenceLegs
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.GeneratedKeyHolder
import org.springframework.stereotype.Repository
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.sql.ResultSet
import java.sql.Statement
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant

/**
 * 실계정 포지션 건·사건·관측 이력 (#129, design D3~D5·D7).
 *
 * [record] 한 번은 한 트랜잭션이다 — 열린 건을 `FOR UPDATE` 로 잠그고 [PositionEpisodePolicy.decide] 결과를 건·사건에
 * 반영한 뒤 관측을 0~1행 남긴다. 관측 INSERT 를 포함해 어느 문장이 실패해도 그 회차의 건·사건은 롤백된다.
 * 다만 관측 값 계산이 불가능한 입력(평단 0 → ArithmeticException)은 DB 오류가 아니라 관측 0행으로 정규화하고 건·사건은 커밋한다.
 *
 * - leg·reference 는 V20 과 같이 `toPlainString()` 10진 문자열로 저장하고 [KoreaLeg.of]·[ForeignLeg.of] 로 복원한다
 *   (DECIMAL 반올림이 다음 회차 비교를 깨지 않도록).
 * - 관측 중복 `(episode_id, observed_at)` 은 `ON DUPLICATE KEY UPDATE id = id` 로 무시한다. 다른 오류는 삼키지 않는다.
 * - 동시 OPENED 경합은 `uk_account_position_episode_open` 위반으로 드러나며 잡지 않고 전파한다.
 * - 로그에는 사건 종류·건 id·seq 만 남긴다(수량·가격 금지).
 */
@Repository
class JdbcAccountPositionHistoryStore(
    private val jdbcTemplate: JdbcTemplate,
    transactionManager: PlatformTransactionManager,
    private val clock: Clock,
) : AccountPositionHistoryStore, AccountReferencePositionReadPort {

    private val log = LoggerFactory.getLogger(javaClass)
    private val transaction = TransactionTemplate(transactionManager)

    override fun record(round: PositionHistoryRound) {
        validateLegs(PositionEpisodePolicy.normalize(round.legs))
        val recorded = transaction.execute { recordInTransaction(round) }
        if (recorded != null) {
            log.info(
                "Account position event recorded: type={}, episodeId={}, seq={}",
                recorded.type,
                recorded.episodeId,
                recorded.seq,
            )
        }
    }

    /** 단일 SELECT 둘이라 트랜잭션·잠금이 필요 없다. 열린 건 reference 우선, 없으면 최근 닫힌 건. */
    override fun findLatestReference(): ReferenceLegs? =
        openEpisodeReference() ?: latestReferenceEpisode()?.second

    private fun recordInTransaction(round: PositionHistoryRound): RecordedEvent? {
        val open = lockOpenEpisode()
        val decision = PositionEpisodePolicy.decide(open, round.legs, round.source, round.observedAt)
        val now = clock.instant().toSqlTimestamp()
        val event = decision.event

        val recorded: RecordedEvent? = when {
            event == null -> null

            event.type == PositionEventType.OPENED -> {
                val id = insertEpisode(event, decision.reference, now)
                insertEvent(id, 1, event, now)
                RecordedEvent(event.type, id, 1)
            }

            else -> {
                val current = checkNotNull(open) { "non-OPENED event requires an open episode." }
                val seq = current.eventCount + 1
                updateEpisode(current.id, event, decision.reference, decision.closes, now)
                insertEvent(current.id, seq, event, now)
                RecordedEvent(event.type, current.id, seq)
            }
        }

        val openEpisodeId = when {
            decision.closes -> null
            recorded?.type == PositionEventType.OPENED -> recorded.episodeId
            else -> open?.id
        }
        recordObservation(round, openEpisodeId, now)
        return recorded
    }

    /** 관측 행 선택 규칙(plan Task 2): 열린 건 + pnl → HOLDING, 열린 건 없음 + 기준 건 → FLAT, 그 밖 0행. */
    private fun recordObservation(round: PositionHistoryRound, openEpisodeId: Long?, now: Timestamp) {
        if (openEpisodeId != null) {
            val pnl = round.pnl ?: return
            val observation = PositionObservation.holdingOrNull(pnl) ?: return
            insertObservation(openEpisodeId, observation, now)
            return
        }
        val (referenceEpisodeId, reference) = latestReferenceEpisode() ?: return
        val observation = PositionObservation.flat(reference, round.market, round.observedAt) ?: return
        insertObservation(referenceEpisodeId, observation, now)
    }

    private fun lockOpenEpisode(): OpenEpisode? =
        jdbcTemplate.query(
            """
            SELECT id, korea_quantity, korea_average_price, foreign_signed_quantity, foreign_entry_price,
                   reference_korea_quantity, reference_korea_price, reference_foreign_quantity, reference_foreign_price,
                   event_count
            FROM account_position_episode
            WHERE status = 'OPEN'
            FOR UPDATE
            """.trimIndent(),
        ) { rs, _ ->
            OpenEpisode(
                id = rs.getLong("id"),
                legs = PositionLegs(
                    korea = KoreaLeg.of(rs.decimal("korea_quantity"), rs.decimal("korea_average_price")),
                    foreign = ForeignLeg.of(rs.decimal("foreign_signed_quantity"), rs.decimal("foreign_entry_price")),
                ),
                reference = rs.toReference(),
                eventCount = rs.getInt("event_count"),
            )
        }.singleOrNull()

    private fun openEpisodeReference(): ReferenceLegs? =
        jdbcTemplate.query(
            """
            SELECT reference_korea_quantity, reference_korea_price, reference_foreign_quantity, reference_foreign_price
            FROM account_position_episode
            WHERE status = 'OPEN' AND reference_korea_quantity IS NOT NULL
            """.trimIndent(),
        ) { rs, _ -> checkNotNull(rs.toReference()) }
            .singleOrNull()

    private fun latestReferenceEpisode(): Pair<Long, ReferenceLegs>? =
        jdbcTemplate.query(
            """
            SELECT id, reference_korea_quantity, reference_korea_price, reference_foreign_quantity, reference_foreign_price
            FROM account_position_episode
            WHERE status = 'CLOSED' AND reference_korea_quantity IS NOT NULL
            ORDER BY closed_at DESC, id DESC
            LIMIT 1
            """.trimIndent(),
        ) { rs, _ -> rs.getLong("id") to checkNotNull(rs.toReference()) }
            .singleOrNull()

    private fun insertEpisode(event: PositionEvent, reference: ReferenceLegs?, now: Timestamp): Long {
        val keyHolder = GeneratedKeyHolder()
        jdbcTemplate.update({ connection ->
            connection.prepareStatement(
                """
                INSERT INTO account_position_episode (
                    status, opened_at, closed_at,
                    korea_quantity, korea_average_price, foreign_signed_quantity, foreign_entry_price,
                    reference_korea_quantity, reference_korea_price, reference_foreign_quantity, reference_foreign_price,
                    position_source, event_count, created_at, updated_at
                ) VALUES ('OPEN', ?, NULL, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?)
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS,
            ).apply {
                val values = listOf<Any?>(
                    event.occurredAt.toSqlTimestamp(),
                    *legArguments(event.legs),
                    *referenceArguments(reference),
                    event.source.name,
                    now,
                    now,
                )
                values.forEachIndexed { index, value -> setObject(index + 1, value) }
            }
        }, keyHolder)
        return checkNotNull(keyHolder.key) { "account_position_episode insert returned no key." }.toLong()
    }

    private fun updateEpisode(id: Long, event: PositionEvent, reference: ReferenceLegs?, closes: Boolean, now: Timestamp) {
        val affected = jdbcTemplate.update(
            """
            UPDATE account_position_episode
            SET korea_quantity = ?,
                korea_average_price = ?,
                foreign_signed_quantity = ?,
                foreign_entry_price = ?,
                reference_korea_quantity = ?,
                reference_korea_price = ?,
                reference_foreign_quantity = ?,
                reference_foreign_price = ?,
                event_count = event_count + 1,
                status = ?,
                closed_at = ?,
                updated_at = ?
            WHERE id = ?
              AND status = 'OPEN'
            """.trimIndent(),
            *legArguments(event.legs),
            *referenceArguments(reference),
            if (closes) "CLOSED" else "OPEN",
            if (closes) event.occurredAt.toSqlTimestamp() else null,
            now,
            id,
        )
        check(affected == 1) { "Locked open account position episode could not be updated: id=$id" }
    }

    private fun insertEvent(episodeId: Long, seq: Int, event: PositionEvent, now: Timestamp) {
        jdbcTemplate.update(
            """
            INSERT INTO account_position_event (
                episode_id, seq, event_type, occurred_at,
                korea_quantity, korea_average_price, foreign_signed_quantity, foreign_entry_price,
                hedged, position_source, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            episodeId,
            seq,
            event.type.name,
            event.occurredAt.toSqlTimestamp(),
            *legArguments(event.legs),
            event.hedged,
            event.source.name,
            now,
        )
    }

    private fun insertObservation(episodeId: Long, observation: PositionObservation, now: Timestamp) {
        jdbcTemplate.update(
            """
            INSERT INTO account_pnl_observation (
                episode_id, kind, observed_at, net_percent, net_krw,
                current_premium_percent, entry_premium_percent, premium_gap_percent_point,
                fx_rate, korea_exit_price, foreign_exit_price, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE id = id
            """.trimIndent(),
            episodeId,
            observation.kind.name,
            observation.observedAt.toSqlTimestamp(),
            observation.netPercent,
            observation.netKrw,
            observation.currentPremiumPercent,
            observation.entryPremiumPercent,
            observation.premiumGapPercentPoint,
            observation.fxRate,
            observation.koreaExitPrice,
            observation.foreignExitPrice,
            now,
        )
    }

    /** 값은 메시지에 넣지 않는다. reference 는 정규화 leg 에서만 만들어지므로 같은 검사로 덮인다. */
    private fun validateLegs(legs: PositionLegs) {
        val values = listOf(
            legs.korea.quantity,
            legs.korea.averageBuyPrice,
            legs.foreign.signedQuantity,
            legs.foreign.entryPrice,
        )
        require(values.all { it.toPlainString().length <= MAX_DECIMAL_TEXT_LENGTH }) {
            "position leg must be at most $MAX_DECIMAL_TEXT_LENGTH characters as a plain decimal."
        }
    }

    private fun legArguments(legs: PositionLegs): Array<Any?> = arrayOf(
        legs.korea.quantity.toPlainString(),
        legs.korea.averageBuyPrice.toPlainString(),
        legs.foreign.signedQuantity.toPlainString(),
        legs.foreign.entryPrice.toPlainString(),
    )

    private fun referenceArguments(reference: ReferenceLegs?): Array<Any?> = arrayOf(
        reference?.koreaQuantity?.toPlainString(),
        reference?.koreaAveragePrice?.toPlainString(),
        reference?.foreignQuantity?.toPlainString(),
        reference?.foreignEntryPrice?.toPlainString(),
    )

    private fun ResultSet.toReference(): ReferenceLegs? {
        val koreaQuantity = getString("reference_korea_quantity") ?: return null
        return ReferenceLegs(
            koreaQuantity = BigDecimal(koreaQuantity),
            koreaAveragePrice = decimal("reference_korea_price"),
            foreignQuantity = decimal("reference_foreign_quantity"),
            foreignEntryPrice = decimal("reference_foreign_price"),
        )
    }

    private fun ResultSet.decimal(column: String): BigDecimal = BigDecimal(getString(column))

    private fun Instant.toSqlTimestamp(): Timestamp = Timestamp.from(this)

    private data class RecordedEvent(val type: PositionEventType, val episodeId: Long, val seq: Int)

    private companion object {
        const val MAX_DECIMAL_TEXT_LENGTH = 64 // VARCHAR(64)
    }
}
