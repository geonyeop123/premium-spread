package io.premiumspread.infrastructure.common.persistence.jdbc.accountpnl

import io.premiumspread.domain.accountpnl.AccountPnlAlertStore
import io.premiumspread.domain.accountpnl.AccountPnlMail
import io.premiumspread.domain.accountpnl.AccountPnlMonitorState
import io.premiumspread.domain.accountpnl.ClaimedAccountPnlMail
import io.premiumspread.domain.accountpnl.OutageState
import io.premiumspread.domain.accountpnl.PnlAlertState
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * 실계정 순손익 알림 상태(단일 행, 낙관적 version)와 전용 메일 outbox.
 *
 * - [save] 는 호출자 트랜잭션에 참여하거나 새로 연다. 상태 행을 먼저 쓰고 실패하면 메일을 쓰기 전에 false 를 돌려주므로
 *   version 불일치 시 아무것도 기록되지 않는다.
 * - [claim] 은 항상 독립 트랜잭션(REQUIRES_NEW)으로 commit 한 뒤 돌려준다 — SMTP 전에 PROCESSING 이 durable 해야 한다.
 * - 이후 전이는 모두 `status = 'PROCESSING' AND claim_token = ?` 로 fencing 한다.
 */
@Repository
class JdbcAccountPnlAlertStore(
    private val jdbcTemplate: JdbcTemplate,
    transactionManager: PlatformTransactionManager,
    private val clock: Clock,
) : AccountPnlAlertStore {

    private val saveTransaction = TransactionTemplate(transactionManager)
    private val claimTransaction = TransactionTemplate(transactionManager).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
    }

    override fun load(): AccountPnlMonitorState =
        jdbcTemplate.query(
            """
            SELECT in_episode, position_fingerprint, last_sent_at,
                   last_success_at, monitoring_since, outage_notified, version
            FROM account_pnl_monitor_state
            WHERE id = $STATE_ID
            """.trimIndent(),
        ) { rs, _ -> rs.toMonitorState() }
            .singleOrNull()
            ?: AccountPnlMonitorState.INITIAL

    override fun save(expectedVersion: Long, state: AccountPnlMonitorState, mails: List<AccountPnlMail>): Boolean {
        require(expectedVersion >= 0) { "expectedVersion must not be negative." }
        mails.forEach(::validate)
        return saveTransaction.execute {
            val stateWritten = if (expectedVersion == 0L) insertState(state) else updateState(expectedVersion, state)
            if (stateWritten) {
                val createdAt = clock.instant()
                mails.forEach { insertMail(it, createdAt) }
            }
            stateWritten
        } == true
    }

    override fun claim(owner: String, limit: Int, now: Instant): List<ClaimedAccountPnlMail> {
        require(owner.isNotBlank()) { "owner must not be blank." }
        require(owner.length <= MAX_OWNER_LENGTH) { "owner must be at most $MAX_OWNER_LENGTH characters." }
        require(limit > 0) { "limit must be positive." }
        return claimTransaction.execute {
            jdbcTemplate.query(
                """
                SELECT id, event_key, subject, body, attempt_count
                FROM account_pnl_mail
                WHERE status = 'PENDING'
                ORDER BY id
                LIMIT ?
                FOR UPDATE SKIP LOCKED
                """.trimIndent(),
                { rs, _ -> rs.toPendingMail() },
                limit,
            ).map { pending -> pending.claimBy(owner, now) }
        }.orEmpty()
    }

    override fun markSent(id: Long, claimToken: String, sentAt: Instant): Boolean =
        jdbcTemplate.update(
            """
            UPDATE account_pnl_mail
            SET status = 'SENT',
                sent_at = ?,
                claim_token = NULL,
                locked_by = NULL,
                locked_at = NULL
            WHERE id = ?
              AND status = 'PROCESSING'
              AND claim_token = ?
            """.trimIndent(),
            sentAt.toSqlTimestamp(),
            id,
            claimToken,
        ) == 1

    /** [now] 는 port 계약상 받지만 outbox 에 시도 시각 컬럼이 없어 기록하지 않는다. */
    override fun markAttemptFailed(id: Long, claimToken: String, now: Instant): Boolean =
        // MySQL 은 SET 을 왼쪽부터 평가하고 뒤 식이 앞에서 바뀐 값을 본다. status 를 먼저 계산해야 증가 전 값을 기준으로 한다.
        jdbcTemplate.update(
            """
            UPDATE account_pnl_mail
            SET status = CASE WHEN attempt_count + 1 >= ? THEN 'FAILED' ELSE 'PENDING' END,
                attempt_count = attempt_count + 1,
                claim_token = NULL,
                locked_by = NULL,
                locked_at = NULL
            WHERE id = ?
              AND status = 'PROCESSING'
              AND claim_token = ?
            """.trimIndent(),
            AccountPnlAlertStore.MAX_ATTEMPTS,
            id,
            claimToken,
        ) == 1

    override fun recoverStale(now: Instant, staleAfter: Duration): Int {
        require(!staleAfter.isNegative) { "staleAfter must not be negative." }
        return jdbcTemplate.update(
            """
            UPDATE account_pnl_mail
            SET status = 'PENDING',
                claim_token = NULL,
                locked_by = NULL,
                locked_at = NULL
            WHERE status = 'PROCESSING'
              AND locked_at < ?
            """.trimIndent(),
            now.minus(staleAfter).toSqlTimestamp(),
        )
    }

    private fun insertState(state: AccountPnlMonitorState): Boolean = try {
        jdbcTemplate.update(
            """
            INSERT INTO account_pnl_monitor_state (
                id, in_episode, position_fingerprint, last_sent_at,
                last_success_at, monitoring_since, outage_notified, version, updated_at
            ) VALUES ($STATE_ID, ?, ?, ?, ?, ?, ?, 1, ?)
            """.trimIndent(),
            state.alert.inEpisode,
            state.alert.positionFingerprint,
            state.alert.lastSentAt?.toSqlTimestamp(),
            state.outage.lastSuccessAt?.toSqlTimestamp(),
            state.outage.monitoringSince?.toSqlTimestamp(),
            state.outage.outageNotified,
            clock.instant().toSqlTimestamp(),
        ) == 1
    } catch (exception: DuplicateKeyException) {
        false
    }

    private fun updateState(expectedVersion: Long, state: AccountPnlMonitorState): Boolean =
        jdbcTemplate.update(
            """
            UPDATE account_pnl_monitor_state
            SET in_episode = ?,
                position_fingerprint = ?,
                last_sent_at = ?,
                last_success_at = ?,
                monitoring_since = ?,
                outage_notified = ?,
                version = version + 1,
                updated_at = ?
            WHERE id = $STATE_ID
              AND version = ?
            """.trimIndent(),
            state.alert.inEpisode,
            state.alert.positionFingerprint,
            state.alert.lastSentAt?.toSqlTimestamp(),
            state.outage.lastSuccessAt?.toSqlTimestamp(),
            state.outage.monitoringSince?.toSqlTimestamp(),
            state.outage.outageNotified,
            clock.instant().toSqlTimestamp(),
            expectedVersion,
        ) == 1

    /** 같은 event_key 는 무시한다. 다른 제약 위반·오류는 그대로 던진다(INSERT IGNORE 를 쓰지 않는 이유). */
    private fun insertMail(mail: AccountPnlMail, createdAt: Instant) {
        jdbcTemplate.update(
            """
            INSERT INTO account_pnl_mail (event_key, subject, body, status, attempt_count, created_at)
            VALUES (?, ?, ?, 'PENDING', 0, ?)
            ON DUPLICATE KEY UPDATE id = id
            """.trimIndent(),
            mail.eventKey,
            mail.subject,
            mail.body,
            createdAt.toSqlTimestamp(),
        )
    }

    private fun validate(mail: AccountPnlMail) {
        require(mail.eventKey.isNotBlank()) { "eventKey must not be blank." }
        require(mail.eventKey.length <= MAX_EVENT_KEY_LENGTH) {
            "eventKey must be at most $MAX_EVENT_KEY_LENGTH characters."
        }
        require(mail.subject.length <= MAX_SUBJECT_LENGTH) { "subject must be at most $MAX_SUBJECT_LENGTH characters." }
    }

    private fun PendingMail.claimBy(owner: String, now: Instant): ClaimedAccountPnlMail {
        val claimToken = UUID.randomUUID().toString()
        val affected = jdbcTemplate.update(
            """
            UPDATE account_pnl_mail
            SET status = 'PROCESSING',
                locked_by = ?,
                claim_token = ?,
                locked_at = ?
            WHERE id = ?
              AND status = 'PENDING'
            """.trimIndent(),
            owner,
            claimToken,
            now.toSqlTimestamp(),
            id,
        )
        check(affected == 1) { "Locked account pnl mail could not be claimed: id=$id" }
        return ClaimedAccountPnlMail(id = id, mail = mail, claimToken = claimToken, attemptCount = attemptCount)
    }

    private fun ResultSet.toMonitorState(): AccountPnlMonitorState = AccountPnlMonitorState(
        alert = PnlAlertState(
            inEpisode = getBoolean("in_episode"),
            positionFingerprint = getString("position_fingerprint"),
            lastSentAt = getInstantOrNull("last_sent_at"),
        ),
        outage = OutageState(
            lastSuccessAt = getInstantOrNull("last_success_at"),
            monitoringSince = getInstantOrNull("monitoring_since"),
            outageNotified = getBoolean("outage_notified"),
        ),
        version = getLong("version"),
    )

    private fun ResultSet.toPendingMail(): PendingMail = PendingMail(
        id = getLong("id"),
        mail = AccountPnlMail(
            eventKey = getString("event_key"),
            subject = getString("subject"),
            body = getString("body"),
        ),
        attemptCount = getInt("attempt_count"),
    )

    private fun ResultSet.getInstantOrNull(column: String): Instant? = getTimestamp(column)?.toInstant()

    private fun Instant.toSqlTimestamp(): Timestamp = Timestamp.from(this)

    private data class PendingMail(val id: Long, val mail: AccountPnlMail, val attemptCount: Int)

    private companion object {
        const val STATE_ID = 1
        const val MAX_OWNER_LENGTH = 64
        const val MAX_EVENT_KEY_LENGTH = 128
        const val MAX_SUBJECT_LENGTH = 255
    }
}
