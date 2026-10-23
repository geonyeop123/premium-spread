package io.premiumspread.application.notification.owneralert

import io.premiumspread.domain.accountpnl.AccountPnlAlertStore
import io.premiumspread.domain.accountpnl.AccountPnlMail
import io.premiumspread.domain.accountpnl.AccountPnlMailSender
import io.premiumspread.domain.accountpnl.AccountPnlMonitorState
import io.premiumspread.domain.accountpnl.ClaimedAccountPnlMail
import io.premiumspread.domain.owneralert.DiscordMessage
import io.premiumspread.domain.owneralert.DiscordRoute
import io.premiumspread.domain.owneralert.OwnerAlertDiscordSender
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

class MutableClock(private var now: Instant) : Clock() {
    fun advance(duration: Duration) {
        now = now.plus(duration)
    }

    override fun instant(): Instant = now
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId?): Clock = this
}

/**
 * 단일 스레드 in-memory owner 알림 outbox. 의미는 `AccountPnlAlertStore` 계약과 JDBC 구현을 따른다:
 * (eventKey, channel) 중복 무시, 낙관적 version, claim token fencing, `markFailed` 즉시 FAILED(attempt+1).
 */
class FakeOwnerAlertStore : AccountPnlAlertStore {
    enum class Status { PENDING, PROCESSING, SENT, FAILED }

    data class Row(
        val id: Long,
        val mail: AccountPnlMail,
        var status: Status = Status.PENDING,
        var token: String? = null,
        var attempts: Int = 0,
    )

    var state = AccountPnlMonitorState.INITIAL
    val rows = mutableListOf<Row>()

    /** `claim` 또는 `<operation>:<id>` 순서 기록. */
    val calls = mutableListOf<String>()
    val owners = mutableListOf<String>()
    var failOn: String? = null
    val fencedOperations = mutableSetOf<String>()

    fun add(mail: AccountPnlMail) {
        if (rows.none { it.mail.eventKey == mail.eventKey && it.mail.channel == mail.channel }) {
            rows += Row(rows.size + 1L, mail)
        }
    }

    fun row(id: Long): Row = rows.single { it.id == id }

    private fun enter(operation: String, detail: String) {
        calls += detail
        if (failOn == operation) throw IllegalStateException("store down: $operation")
    }

    override fun load(): AccountPnlMonitorState = state

    override fun save(expectedVersion: Long, state: AccountPnlMonitorState, mails: List<AccountPnlMail>): Boolean {
        if (this.state.version != expectedVersion) return false
        this.state = state.copy(version = expectedVersion + 1)
        mails.forEach(::add)
        return true
    }

    override fun claim(owner: String, limit: Int, now: Instant): List<ClaimedAccountPnlMail> {
        enter("claim", "claim")
        owners += owner
        return rows.filter { it.status == Status.PENDING }.sortedBy { it.id }.take(limit).map { row ->
            row.status = Status.PROCESSING
            row.token = UUID.randomUUID().toString()
            ClaimedAccountPnlMail(row.id, row.mail, row.token!!, row.attempts)
        }
    }

    override fun markSent(id: Long, claimToken: String, sentAt: Instant): Boolean =
        transition("markSent", id, claimToken) { it.status = Status.SENT }

    override fun markAttemptFailed(id: Long, claimToken: String, now: Instant): Boolean =
        transition("markAttemptFailed", id, claimToken) {
            it.attempts += 1
            it.status = if (it.attempts >= AccountPnlAlertStore.MAX_ATTEMPTS) Status.FAILED else Status.PENDING
        }

    override fun markFailed(id: Long, claimToken: String, now: Instant): Boolean =
        transition("markFailed", id, claimToken) {
            it.attempts += 1
            it.status = Status.FAILED
        }

    override fun recoverStale(now: Instant, staleAfter: Duration): Int = 0

    private fun transition(operation: String, id: Long, token: String, apply: (Row) -> Unit): Boolean {
        enter(operation, "$operation:$id")
        if (operation in fencedOperations) return false
        val row = rows.firstOrNull { it.id == id && it.status == Status.PROCESSING && it.token == token } ?: return false
        apply(row)
        row.token = null
        return true
    }
}

class FakeMailSender : AccountPnlMailSender {
    var failure: (AccountPnlMail) -> Exception? = { null }
    val attempts = mutableListOf<AccountPnlMail>()
    val sent = mutableListOf<AccountPnlMail>()

    override fun send(mail: AccountPnlMail) {
        attempts += mail
        failure(mail)?.let { throw it }
        sent += mail
    }
}

class FakeDiscordSender : OwnerAlertDiscordSender {
    var failure: (DiscordMessage) -> Exception? = { null }
    var onSend: () -> Unit = {}
    val attempts = mutableListOf<Pair<DiscordRoute, DiscordMessage>>()
    val sent = mutableListOf<Pair<DiscordRoute, DiscordMessage>>()

    override fun send(route: DiscordRoute, message: DiscordMessage) {
        attempts += route to message
        onSend()
        failure(message)?.let { throw it }
        sent += route to message
    }
}
