package io.premiumspread.infrastructure.common.persistence.jdbc.accountpnl

import io.premiumspread.domain.accountpnl.AccountAlertState
import io.premiumspread.domain.accountpnl.AccountPnlAlertStore
import io.premiumspread.domain.accountpnl.AccountPnlMail
import io.premiumspread.domain.accountpnl.AccountPnlMonitorState
import io.premiumspread.domain.accountpnl.ClaimedAccountPnlMail
import io.premiumspread.domain.accountpnl.ForeignLeg
import io.premiumspread.domain.accountpnl.KoreaLeg
import io.premiumspread.domain.accountpnl.OutageState
import io.premiumspread.domain.accountpnl.PnlAlertState
import io.premiumspread.domain.accountpnl.PositionLegs
import io.premiumspread.domain.accountpnl.PositionWatchState
import io.premiumspread.domain.accountpnl.ReminderState
import io.premiumspread.domain.accountpnl.SwingState
import io.premiumspread.domain.owneralert.DiscordPush
import io.premiumspread.domain.owneralert.DiscordRoute
import io.premiumspread.domain.owneralert.DiscordTarget
import io.premiumspread.domain.owneralert.OwnerAlertChannel
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * 실계정 순손익 알림 상태(단일 행, 낙관적 version)와 owner 알림 공용 outbox(메일·Discord).
 *
 * 상태 행은 수익·중단 + owner 계정 알림(#89) 상태다.
 *
 * 한 알림은 채널마다 한 행이며 중복 제거는 `(event_key, channel)` UNIQUE 가 한다. 이 binary 이전 버전의 claim 은
 * channel 을 모른다 — 구·신 batch 를 겹쳐 돌리지 않는다(runbook 배포와 롤백).
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
                   last_success_at, monitoring_since, outage_notified, version,
                   korea_quantity, korea_average_buy_price, foreign_signed_quantity, foreign_entry_price,
                   hedge_mismatch_warned, swing_bucket_start, swing_baseline_percent,
                   swing_up_sent, swing_down_sent, reminder_day_start,
                   manual_funding_usdt, manual_funding_changed_at
            FROM account_pnl_monitor_state
            WHERE id = $STATE_ID
            """.trimIndent(),
        ) { rs, _ -> rs.toMonitorState() }
            .singleOrNull()
            ?: AccountPnlMonitorState.INITIAL

    override fun save(expectedVersion: Long, state: AccountPnlMonitorState, mails: List<AccountPnlMail>): Boolean {
        require(expectedVersion >= 0) { "expectedVersion must not be negative." }
        validateState(state.account)
        mails.forEach(OwnerAlertOutboxRows::validate)
        return saveTransaction.execute {
            val stateWritten = if (expectedVersion == 0L) insertState(state) else updateState(expectedVersion, state)
            if (stateWritten) {
                val createdAt = clock.instant()
                mails.forEach { OwnerAlertOutboxRows.insert(jdbcTemplate, it, createdAt) }
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
                SELECT id, event_key, subject, body, attempt_count,
                       channel, discord_target, discord_push, discord_color
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

    /**
     * 재시도해도 성공할 수 없는 실패 — 시도 횟수와 무관하게 바로 `FAILED`(attempt+1).
     * [now] 는 [markAttemptFailed] 와 같은 이유(시도 시각 컬럼 없음)로 기록하지 않는다.
     */
    override fun markFailed(id: Long, claimToken: String, now: Instant): Boolean =
        jdbcTemplate.update(
            """
            UPDATE account_pnl_mail
            SET status = 'FAILED',
                attempt_count = attempt_count + 1,
                claim_token = NULL,
                locked_by = NULL,
                locked_at = NULL
            WHERE id = ?
              AND status = 'PROCESSING'
              AND claim_token = ?
            """.trimIndent(),
            id,
            claimToken,
        ) == 1

    /** stale claim 도 한 번의 시도로 센다 — 발송 중 반복 crash 가 무한 재시도가 되지 않도록 (design §4.4). */
    override fun recoverStale(now: Instant, staleAfter: Duration): Int {
        require(!staleAfter.isNegative) { "staleAfter must not be negative." }
        // markAttemptFailed 와 같이 status 를 attempt_count 증가보다 먼저 계산한다.
        return jdbcTemplate.update(
            """
            UPDATE account_pnl_mail
            SET status = CASE WHEN attempt_count + 1 >= ? THEN 'FAILED' ELSE 'PENDING' END,
                attempt_count = attempt_count + 1,
                claim_token = NULL,
                locked_by = NULL,
                locked_at = NULL
            WHERE status = 'PROCESSING'
              AND locked_at < ?
            """.trimIndent(),
            AccountPnlAlertStore.MAX_ATTEMPTS,
            now.minus(staleAfter).toSqlTimestamp(),
        )
    }

    private fun insertState(state: AccountPnlMonitorState): Boolean = try {
        jdbcTemplate.update(
            """
            INSERT INTO account_pnl_monitor_state (
                id, in_episode, position_fingerprint, last_sent_at,
                last_success_at, monitoring_since, outage_notified, version, updated_at,
                korea_quantity, korea_average_buy_price, foreign_signed_quantity, foreign_entry_price,
                hedge_mismatch_warned, swing_bucket_start, swing_baseline_percent,
                swing_up_sent, swing_down_sent, reminder_day_start,
                manual_funding_usdt, manual_funding_changed_at
            ) VALUES ($STATE_ID, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            state.alert.inEpisode,
            state.alert.positionFingerprint,
            state.alert.lastSentAt?.toSqlTimestamp(),
            state.outage.lastSuccessAt?.toSqlTimestamp(),
            state.outage.monitoringSince?.toSqlTimestamp(),
            state.outage.outageNotified,
            clock.instant().toSqlTimestamp(),
            *accountArguments(state.account),
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
                updated_at = ?,
                korea_quantity = ?,
                korea_average_buy_price = ?,
                foreign_signed_quantity = ?,
                foreign_entry_price = ?,
                hedge_mismatch_warned = ?,
                swing_bucket_start = ?,
                swing_baseline_percent = ?,
                swing_up_sent = ?,
                swing_down_sent = ?,
                reminder_day_start = ?,
                manual_funding_usdt = ?,
                manual_funding_changed_at = ?
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
            *accountArguments(state.account),
            expectedVersion,
        ) == 1

    /** design §4.3 표 순서. leg·펀딩은 지수 표기를 피하려 `toPlainString()` 으로 저장한다. */
    private fun accountArguments(account: AccountAlertState): Array<Any?> {
        val legs = account.position.legs
        return arrayOf(
            legs?.korea?.quantity?.toPlainString(),
            legs?.korea?.averageBuyPrice?.toPlainString(),
            legs?.foreign?.signedQuantity?.toPlainString(),
            legs?.foreign?.entryPrice?.toPlainString(),
            account.position.hedgeMismatchWarned,
            account.swing.bucketStart?.toSqlTimestamp(),
            account.swing.baselinePercent,
            account.swing.upSent,
            account.swing.downSent,
            account.reminder.lastDayStart?.toSqlTimestamp(),
            account.reminder.manualFundingUsdt?.toPlainString(),
            account.reminder.manualFundingChangedAt?.toSqlTimestamp(),
        )
    }

    /** 값은 메시지에 넣지 않는다. */
    private fun validateState(account: AccountAlertState) {
        account.position.legs?.let { legs ->
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
        account.reminder.manualFundingUsdt?.let {
            require(it.toPlainString().length <= MAX_DECIMAL_TEXT_LENGTH) {
                "manual funding must be at most $MAX_DECIMAL_TEXT_LENGTH characters as a plain decimal."
            }
        }
        account.swing.baselinePercent?.let {
            require(it.abs() < MAX_BASELINE_PERCENT_EXCLUSIVE) { "swing baseline percent is out of range." }
        }
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
        account = toAccountState(),
    )

    private fun ResultSet.toAccountState(): AccountAlertState {
        val koreaQuantity = getString("korea_quantity")
        val legs = if (koreaQuantity == null) {
            null
        } else {
            PositionLegs(
                korea = KoreaLeg.of(BigDecimal(koreaQuantity), BigDecimal(getString("korea_average_buy_price"))),
                foreign = ForeignLeg.of(
                    BigDecimal(getString("foreign_signed_quantity")),
                    BigDecimal(getString("foreign_entry_price")),
                ),
            )
        }
        return AccountAlertState(
            position = PositionWatchState(legs, getBoolean("hedge_mismatch_warned")),
            swing = SwingState(
                bucketStart = getInstantOrNull("swing_bucket_start"),
                baselinePercent = getBigDecimal("swing_baseline_percent"),
                upSent = getBoolean("swing_up_sent"),
                downSent = getBoolean("swing_down_sent"),
            ),
            reminder = ReminderState(
                lastDayStart = getInstantOrNull("reminder_day_start"),
                manualFundingUsdt = getString("manual_funding_usdt")?.let { BigDecimal(it).stripTrailingZeros() },
                manualFundingChangedAt = getInstantOrNull("manual_funding_changed_at"),
            ),
        )
    }

    private fun ResultSet.toPendingMail(): PendingMail {
        val channel = OwnerAlertChannel.valueOf(getString("channel"))
        val discord = channel == OwnerAlertChannel.DISCORD
        return PendingMail(
            id = getLong("id"),
            mail = AccountPnlMail(
                eventKey = getString("event_key"),
                subject = getString("subject"),
                body = getString("body"),
                channel = channel,
                discordRoute = if (discord) {
                    DiscordRoute(
                        target = DiscordTarget.valueOf(getString("discord_target")),
                        push = DiscordPush.valueOf(getString("discord_push")),
                    )
                } else {
                    null
                },
                discordColor = if (discord) getInt("discord_color") else null,
            ),
            attemptCount = getInt("attempt_count"),
        )
    }

    private fun ResultSet.getInstantOrNull(column: String): Instant? = getTimestamp(column)?.toInstant()

    private fun Instant.toSqlTimestamp(): Timestamp = Timestamp.from(this)

    private data class PendingMail(val id: Long, val mail: AccountPnlMail, val attemptCount: Int)

    private companion object {
        const val STATE_ID = 1
        const val MAX_OWNER_LENGTH = 64
        const val MAX_DECIMAL_TEXT_LENGTH = 64 // VARCHAR(64)
        val MAX_BASELINE_PERCENT_EXCLUSIVE: BigDecimal = BigDecimal.TEN.pow(10) // DECIMAL(12,2)
    }
}
