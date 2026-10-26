package io.premiumspread.domain.accountpnl

import java.time.ZoneId

data class AccountAlertDecision(val next: AccountAlertState, val alerts: List<AccountAlert>)

/** 세 정책(포지션 변경·순수익률 급변·미반영 리마인더)을 한 회차에 조합한다 (design §3.5). */
object AccountAlertPolicy {
    /**
     * design §3.5. [round] == null(실패 회차)이면 (state, []).
     * 순서: 포지션 → 급변(positionChanged = 포지션 알림 있음, bucket = NetPercentSwingPolicy.bucketStart(round.observedAt, zone))
     *       → 리마인더. alerts 도 이 순서.
     */
    fun decide(
        state: AccountAlertState,
        round: AccountRound?,
        settings: AccountAlertSettings,
        zone: ZoneId,
    ): AccountAlertDecision {
        if (round == null) return AccountAlertDecision(state, emptyList())

        val position = PositionChangePolicy.decide(state.position, round.legs, round.source, round.observedAt)
        val bucket = NetPercentSwingPolicy.bucketStart(round.observedAt, zone)
        val swing = NetPercentSwingPolicy.decide(state.swing, bucket, round.pnl, position.alert != null, settings.swingThreshold)
        val reminder = UnrecoveredReminderPolicy.decide(state.reminder, round, settings, zone)

        val alerts = listOfNotNull(position.alert, swing.alert, reminder.alert)
        return AccountAlertDecision(AccountAlertState(position.next, swing.next, reminder.next), alerts)
    }
}
