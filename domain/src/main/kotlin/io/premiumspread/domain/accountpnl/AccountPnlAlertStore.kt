package io.premiumspread.domain.accountpnl

import java.time.Duration
import java.time.Instant

/** 수익 알림·중단 알림 상태와 낙관적 버전. `version == 0` 이면 아직 저장된 행이 없다. */
data class AccountPnlMonitorState(val alert: PnlAlertState, val outage: OutageState, val version: Long) {
    companion object {
        val INITIAL = AccountPnlMonitorState(PnlAlertState.INITIAL, OutageState.INITIAL, 0)
    }
}

/** `PROCESSING` 으로 claim 된 메일. 이후 상태 전이는 [claimToken] 이 일치할 때만 반영된다. */
data class ClaimedAccountPnlMail(val id: Long, val mail: AccountPnlMail, val claimToken: String, val attemptCount: Int)

/**
 * 실계정 순손익 알림 상태와 전용 메일 outbox (design §4.4).
 *
 * 발송은 at-least-once 다. SMTP 전에 claim 을 commit 하고, 모든 전이는 claim token 으로 fencing 한다.
 */
interface AccountPnlAlertStore {
    /** 저장된 행이 없으면 [AccountPnlMonitorState.INITIAL] (version 0). */
    fun load(): AccountPnlMonitorState

    /**
     * 한 트랜잭션: 저장된 version 이 [expectedVersion] 과 같을 때만 상태를 갱신(version+1)하고 메일을 넣는다.
     * 같은 eventKey 메일은 무시한다. version 이 다르면 아무것도 쓰지 않고 false.
     */
    fun save(expectedVersion: Long, state: AccountPnlMonitorState, mails: List<AccountPnlMail>): Boolean

    /** 짧은 트랜잭션: `PENDING` 을 id 오름차순으로 최대 [limit] 개 `PROCESSING` 으로 잡고 commit 한 뒤 돌려준다. */
    fun claim(owner: String, limit: Int, now: Instant): List<ClaimedAccountPnlMail>

    /** token 이 일치하는 `PROCESSING` 행만 `SENT` 로 바꾼다. 불일치면 false. */
    fun markSent(id: Long, claimToken: String, sentAt: Instant): Boolean

    /** 시도 횟수 +1. [MAX_ATTEMPTS] 에 도달하면 `FAILED`, 아니면 `PENDING`. token 불일치면 false. */
    fun markAttemptFailed(id: Long, claimToken: String, now: Instant): Boolean

    /**
     * 재시도해도 성공할 수 없는 실패. token 이 일치하는 `PROCESSING` 행을 즉시 `FAILED`(attempt+1)로. 불일치면 false.
     * 기본 구현은 구분하지 못하는 구현을 위해 시도 실패로 센다.
     */
    fun markFailed(id: Long, claimToken: String, now: Instant): Boolean = markAttemptFailed(id, claimToken, now)

    /**
     * `locked_at < now − staleAfter` 인 `PROCESSING` 의 token 을 지우고 한 번의 시도로 센다(attempt+1).
     * [MAX_ATTEMPTS] 에 도달하면 `FAILED`, 아니면 `PENDING`. 반환값은 전이한 행 수(두 경우 모두 포함).
     */
    fun recoverStale(now: Instant, staleAfter: Duration): Int

    companion object {
        const val MAX_ATTEMPTS: Int = 5
    }
}

/** 메일 발송 port. 수신자는 adapter 설정이 소유한다. */
fun interface AccountPnlMailSender {
    fun send(mail: AccountPnlMail)
}
