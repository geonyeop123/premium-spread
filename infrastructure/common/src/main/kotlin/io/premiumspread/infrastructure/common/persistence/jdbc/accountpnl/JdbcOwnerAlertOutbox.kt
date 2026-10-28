package io.premiumspread.infrastructure.common.persistence.jdbc.accountpnl

import io.premiumspread.domain.accountpnl.AccountPnlMail
import io.premiumspread.domain.accountpnl.OwnerAlertIncident
import io.premiumspread.domain.accountpnl.OwnerAlertOutbox
import io.premiumspread.domain.accountpnl.OwnerAlertSignalState
import io.premiumspread.domain.owneralert.OwnerAlertKind
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant

/**
 * OwnerAlertOutbox 구현 — 신호 상태(owner_alert_signal_state) + outbox 행 한 트랜잭션 (owner-ops-alerts design §4.3).
 *
 * [enqueue] 는 호출자 트랜잭션 없이 호출한다. 참여하면 version 충돌 시 setRollbackOnly 가 바깥 트랜잭션 전체를
 * rollback-only 로 만들어 UnexpectedRollbackException 이 난다. 자체 TransactionTemplate 로 새로 연다.
 * 신호 version 이 하나라도 맞지 않으면 rollback-only 로 표시해 앞서 쓴 신호까지 되돌리고 false 를 돌려준다. 행은 모든 신호가 성공한 뒤에만 넣는다.
 */
@Repository
class JdbcOwnerAlertOutbox(
    private val jdbcTemplate: JdbcTemplate,
    transactionManager: PlatformTransactionManager,
    private val clock: Clock,
) : OwnerAlertOutbox {

    private val transaction = TransactionTemplate(transactionManager)

    override fun loadSignals(signalKeys: Collection<String>): Map<String, OwnerAlertSignalState> {
        val keys = signalKeys.distinct()
        if (keys.isEmpty()) return emptyMap()
        val placeholders = keys.joinToString(", ") { "?" }
        return jdbcTemplate.query(
            "SELECT signal_key, alert_kind, active, incident_started_at, last_notified_at, notify_count, version " +
                "FROM owner_alert_signal_state WHERE signal_key IN ($placeholders)",
            { rs, _ -> rs.toSignalState() },
            *keys.toTypedArray(),
        ).associateBy { it.signalKey }
    }

    override fun enqueue(signals: List<OwnerAlertSignalState>, rows: List<AccountPnlMail>): Boolean {
        require(signals.map { it.signalKey }.toSet().size == signals.size) { "signal keys must be distinct." }
        rows.forEach(OwnerAlertOutboxRows::validate)
        if (signals.isEmpty() && rows.isEmpty()) return true
        return transaction.execute { status ->
            val now = clock.instant()
            val allWritten = signals.all { write(it, now) }
            if (!allWritten) {
                status.setRollbackOnly()
                return@execute false
            }
            rows.forEach { OwnerAlertOutboxRows.insert(jdbcTemplate, it, now) }
            true
        } == true
    }

    private fun write(signal: OwnerAlertSignalState, now: Instant): Boolean =
        if (signal.version == 0L) insert(signal, now) else update(signal, now)

    private fun insert(signal: OwnerAlertSignalState, now: Instant): Boolean = try {
        jdbcTemplate.update(
            "INSERT INTO owner_alert_signal_state " +
                "(signal_key, alert_kind, active, incident_started_at, last_notified_at, notify_count, version, updated_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, 1, ?)",
            signal.signalKey,
            signal.alertKind.name,
            signal.active,
            signal.incident?.startedAt?.toSqlTimestamp(),
            signal.incident?.lastNotifiedAt?.toSqlTimestamp(),
            signal.incident?.notifyCount ?: 0,
            now.toSqlTimestamp(),
        ) == 1
    } catch (exception: DuplicateKeyException) {
        false
    }

    private fun update(signal: OwnerAlertSignalState, now: Instant): Boolean =
        jdbcTemplate.update(
            "UPDATE owner_alert_signal_state SET alert_kind = ?, active = ?, incident_started_at = ?, " +
                "last_notified_at = ?, notify_count = ?, version = version + 1, updated_at = ? " +
                "WHERE signal_key = ? AND version = ?",
            signal.alertKind.name,
            signal.active,
            signal.incident?.startedAt?.toSqlTimestamp(),
            signal.incident?.lastNotifiedAt?.toSqlTimestamp(),
            signal.incident?.notifyCount ?: 0,
            now.toSqlTimestamp(),
            signal.signalKey,
            signal.version,
        ) == 1

    private fun ResultSet.toSignalState(): OwnerAlertSignalState {
        val kind = OwnerAlertKind.entries.firstOrNull { it.name == getString("alert_kind") }
            ?: throw IllegalStateException("unknown owner alert kind in owner_alert_signal_state")
        val incident = if (getBoolean("active")) {
            OwnerAlertIncident(
                startedAt = getTimestamp("incident_started_at").toInstant(),
                lastNotifiedAt = getTimestamp("last_notified_at").toInstant(),
                notifyCount = getInt("notify_count"),
            )
        } else {
            null
        }
        return OwnerAlertSignalState(getString("signal_key"), kind, incident, getLong("version"))
    }

    private fun Instant.toSqlTimestamp(): Timestamp = Timestamp.from(this)
}
