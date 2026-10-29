package io.premiumspread.infrastructure.common.persistence.jdbc.ownermarket

import io.premiumspread.domain.accountpnl.AccountPnlMail
import io.premiumspread.domain.owneralert.OwnerAlertKind
import io.premiumspread.domain.ownermarket.MarketAlertState
import io.premiumspread.domain.ownermarket.OwnerMarketAlertStore
import io.premiumspread.domain.ownermarket.PremiumZone
import io.premiumspread.domain.ownermarket.ThresholdBaseline
import io.premiumspread.infrastructure.common.persistence.jdbc.accountpnl.OwnerAlertOutboxRows
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.sql.Date
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant

/**
 * OwnerMarketAlertStore 구현 — 시장 알림 상태(owner_market_alert_state) + outbox 행 한 트랜잭션 (owner-market-alerts design §5.5).
 *
 * [enqueue] 는 호출자 트랜잭션 없이 호출한다. 참여하면 version 충돌 시 setRollbackOnly 가 바깥 트랜잭션 전체를
 * rollback-only 로 만들어 UnexpectedRollbackException 이 난다. 자체 TransactionTemplate 로 새로 연다.
 * 상태 version 이 하나라도 맞지 않으면 rollback-only 로 표시해 앞서 쓴 상태까지 되돌리고 false 를 돌려준다. 행은 모든 상태가 성공한 뒤에만 넣는다.
 */
@Repository
class JdbcOwnerMarketAlertStore(
    private val jdbcTemplate: JdbcTemplate,
    transactionManager: PlatformTransactionManager,
    private val clock: Clock,
) : OwnerMarketAlertStore {

    private val transaction = TransactionTemplate(transactionManager)

    override fun load(signalKeys: Collection<String>): Map<String, MarketAlertState> {
        val keys = signalKeys.distinct()
        if (keys.isEmpty()) return emptyMap()
        val placeholders = keys.joinToString(", ") { "?" }
        return jdbcTemplate.query(
            "SELECT signal_key, alert_kind, last_notified_at, premium_zone, upper_percent, lower_percent, " +
                "hysteresis_percent, summary_date, version " +
                "FROM owner_market_alert_state WHERE signal_key IN ($placeholders)",
            { rs, _ -> rs.toState() },
            *keys.toTypedArray(),
        ).associateBy { it.signalKey }
    }

    override fun enqueue(states: List<MarketAlertState>, rows: List<AccountPnlMail>): Boolean {
        require(states.map { it.signalKey }.toSet().size == states.size) { "signal keys must be distinct." }
        rows.forEach(OwnerAlertOutboxRows::validate)
        if (states.isEmpty() && rows.isEmpty()) return true
        return transaction.execute { status ->
            val now = clock.instant()
            val allWritten = states.all { write(it, now) }
            if (!allWritten) {
                status.setRollbackOnly()
                return@execute false
            }
            rows.forEach { OwnerAlertOutboxRows.insert(jdbcTemplate, it, now) }
            true
        } == true
    }

    private fun write(state: MarketAlertState, now: Instant): Boolean =
        if (state.version == 0L) insert(state, now) else update(state, now)

    private fun insert(state: MarketAlertState, now: Instant): Boolean = try {
        jdbcTemplate.update(
            "INSERT INTO owner_market_alert_state " +
                "(signal_key, alert_kind, last_notified_at, premium_zone, upper_percent, lower_percent, " +
                "hysteresis_percent, summary_date, version, updated_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1, ?)",
            state.signalKey,
            state.alertKind.name,
            state.lastNotifiedAt?.toSqlTimestamp(),
            state.zone?.name,
            state.baseline?.upper,
            state.baseline?.lower,
            state.baseline?.hysteresis,
            state.summaryDate?.let { Date.valueOf(it) },
            now.toSqlTimestamp(),
        ) == 1
    } catch (exception: DuplicateKeyException) {
        false
    }

    private fun update(state: MarketAlertState, now: Instant): Boolean =
        jdbcTemplate.update(
            "UPDATE owner_market_alert_state SET alert_kind = ?, last_notified_at = ?, premium_zone = ?, " +
                "upper_percent = ?, lower_percent = ?, hysteresis_percent = ?, summary_date = ?, " +
                "version = version + 1, updated_at = ? " +
                "WHERE signal_key = ? AND version = ?",
            state.alertKind.name,
            state.lastNotifiedAt?.toSqlTimestamp(),
            state.zone?.name,
            state.baseline?.upper,
            state.baseline?.lower,
            state.baseline?.hysteresis,
            state.summaryDate?.let { Date.valueOf(it) },
            now.toSqlTimestamp(),
            state.signalKey,
            state.version,
        ) == 1

    private fun ResultSet.toState(): MarketAlertState {
        val kind = OwnerAlertKind.entries.firstOrNull { it.name == getString("alert_kind") }
            ?: throw IllegalStateException("unknown owner alert kind in owner_market_alert_state")
        val zoneName = getString("premium_zone")
        val zone = zoneName?.let { name ->
            PremiumZone.entries.firstOrNull { it.name == name }
                ?: throw IllegalStateException("unknown premium zone in owner_market_alert_state")
        }
        val upper = getBigDecimal("upper_percent")
        val lower = getBigDecimal("lower_percent")
        val hysteresis = getBigDecimal("hysteresis_percent")
        return MarketAlertState(
            signalKey = getString("signal_key"),
            alertKind = kind,
            lastNotifiedAt = getTimestamp("last_notified_at")?.toInstant(),
            zone = zone,
            baseline = baselineOf(upper, lower, hysteresis),
            summaryDate = getDate("summary_date")?.toLocalDate(),
            version = getLong("version"),
        )
    }

    private fun baselineOf(upper: BigDecimal?, lower: BigDecimal?, hysteresis: BigDecimal?): ThresholdBaseline? =
        if (hysteresis == null) null else ThresholdBaseline(upper, lower, hysteresis)

    private fun Instant.toSqlTimestamp(): Timestamp = Timestamp.from(this)
}
