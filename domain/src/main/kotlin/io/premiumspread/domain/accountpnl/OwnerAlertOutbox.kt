package io.premiumspread.domain.accountpnl

import io.premiumspread.domain.owneralert.DiscordLayout
import io.premiumspread.domain.owneralert.DiscordRoute
import io.premiumspread.domain.owneralert.OwnerAlertChannel
import io.premiumspread.domain.owneralert.OwnerAlertKind
import java.time.Instant

/** 진행 중인 장애 한 건. [notifyCount] = 지금까지 보낸 장애 알림 수(첫 알림 포함). */
data class OwnerAlertIncident(val startedAt: Instant, val lastNotifiedAt: Instant, val notifyCount: Int) {
    init {
        require(notifyCount >= 1) { "notifyCount must be at least 1." }
        require(!lastNotifiedAt.isBefore(startedAt)) { "lastNotifiedAt must not be before startedAt." }
    }
}

/**
 * owner 알림 신호 하나의 지속 상태 (owner_alert_signal_state 한 행). [incident] null = 정상.
 * [version] 0 = 아직 행이 없다. 저장은 [OwnerAlertOutbox.enqueue] 가 version 조건부로 한다.
 */
data class OwnerAlertSignalState(
    val signalKey: String,
    val alertKind: OwnerAlertKind,
    val incident: OwnerAlertIncident?,
    val version: Long,
) {
    init {
        require(SIGNAL_KEY_PATTERN.matches(signalKey)) { "signalKey must match [a-z0-9._-]{1,64}." }
        require(version >= 0) { "version must not be negative." }
    }

    val active: Boolean get() = incident != null

    companion object {
        val SIGNAL_KEY_PATTERN: Regex = Regex("[a-z0-9._-]{1,64}")

        fun initial(signalKey: String, alertKind: OwnerAlertKind): OwnerAlertSignalState =
            OwnerAlertSignalState(signalKey, alertKind, null, 0)
    }
}

/**
 * owner 알림 공용 enqueue port — PnL 모니터 밖의 생산자(#91 운영, #90 시장)가 outbox(account_pnl_mail)에 행을 넣는다.
 * 신호 상태 갱신과 행 삽입은 한 트랜잭션이다(DB-first). 외부 발송은 전달 Job 의 claim commit 뒤다.
 */
interface OwnerAlertOutbox {
    /** [signalKeys] 중 저장된 행만 돌려준다. 없는 key 는 map 에 없다. */
    fun loadSignals(signalKeys: Collection<String>): Map<String, OwnerAlertSignalState>

    /**
     * 한 트랜잭션: [signals] 각각을 version 조건부로 쓰고
     * (version 0 = INSERT, 그 밖 = `version = ?` 인 UPDATE, 저장 후 version+1)
     * 모두 성공하면 [rows] 를 넣는다. 같은 (eventKey, channel) 행은 무시한다.
     * 하나라도 version 이 맞지 않으면 아무것도 쓰지 않고 false. [signals] 의 signalKey 는 서로 달라야 한다.
     * 호출자 트랜잭션 밖에서 호출한다 — 구현이 자체 트랜잭션을 연다.
     */
    fun enqueue(signals: List<OwnerAlertSignalState>, rows: List<AccountPnlMail>): Boolean
}

/**
 * owner 알림 공용 outbox 행 (이름은 호환 때문에 유지 — design §4.4).
 * EMAIL 행: subject/body = 메일 제목/본문. DISCORD 행: subject/body = embed 제목/설명, Discord 필드 필수.
 */
data class AccountPnlMail(
    val eventKey: String,
    val subject: String,
    val body: String,
    val channel: OwnerAlertChannel = OwnerAlertChannel.EMAIL,
    val discordRoute: DiscordRoute? = null,
    val discordColor: Int? = null,
    /** DISCORD 행의 embed 레이아웃. null = 제목·설명만(V23 이전 행 포함). */
    val discordLayout: DiscordLayout? = null,
) {
    init {
        val discord = channel == OwnerAlertChannel.DISCORD
        require(discord == (discordRoute != null)) { "discordRoute must be set only for DISCORD rows." }
        require(discord == (discordColor != null)) { "discordColor must be set only for DISCORD rows." }
        require(discord || discordLayout == null) { "discordLayout must be set only for DISCORD rows." }
    }
}
