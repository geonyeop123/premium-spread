package io.premiumspread.domain.accountpnl

import java.time.Duration
import java.time.Instant
import java.time.ZoneId

data class ReminderDecision(val next: ReminderState, val alert: AccountAlert.UnrecoveredReminder?)

object UnrecoveredReminderPolicy {
    /** zone 날짜의 자정. 범위 [dayStart, 다음 dayStart). */
    fun dayStart(at: Instant, zone: ZoneId): Instant = at.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant()

    /**
     * design §3.4 — 1단계 수동 펀딩 추적(모든 성공 회차) → 2단계 R1~R5(사용자 확정 D1).
     * 설정 시각 이후 포지션 있는 회차마다 판정하고, 항목이 있는 첫 회차에 보낸다. lastDayStart 는 **보냈을 때만** 갱신한다.
     */
    fun decide(state: ReminderState, round: AccountRound, settings: AccountAlertSettings, zone: ZoneId): ReminderDecision {
        val tracked = trackFunding(state, round)
        val pnl = round.pnl
        val observedAt = round.observedAt
        val dayStart = dayStart(observedAt, zone)
        // 분기 순서 = R1 포지션 없음, R2 설정 시각 전, R3 오늘 이미 보냄, 그 밖 R4·R5
        return when {
            pnl == null -> ReminderDecision(tracked, null)
            observedAt.atZone(zone).hour < settings.reminderHour -> ReminderDecision(tracked, null)
            tracked.lastDayStart == dayStart -> ReminderDecision(tracked, null)
            else -> remind(tracked, round, pnl, settings, dayStart)
        }
    }

    private fun remind(
        tracked: ReminderState,
        round: AccountRound,
        pnl: AccountNetPnl,
        settings: AccountAlertSettings,
        dayStart: Instant,
    ): ReminderDecision {
        val staleness = staleness(tracked, round, settings)
        val items = buildList {
            if (pnl.koreaEntryFeeUnrecovered) add(ReminderItem.KOREA_ENTRY_FEE)
            if (pnl.foreignEntryFeeUnrecovered) add(ReminderItem.FOREIGN_ENTRY_FEE)
            if (pnl.fundingUnrecovered) add(ReminderItem.FUNDING)
            if (staleness != null) add(ReminderItem.MANUAL_FUNDING_STALE)
        }
        if (items.isEmpty()) return ReminderDecision(tracked, null) // R4

        val alert = AccountAlert.UnrecoveredReminder(
            dayStart = dayStart,
            items = items,
            source = round.source,
            manualFunding = staleness,
            reminderHour = settings.reminderHour,
            detectedAt = round.observedAt,
        )
        return ReminderDecision(tracked.copy(lastDayStart = dayStart), alert) // R5
    }

    private fun trackFunding(state: ReminderState, round: AccountRound): ReminderState {
        if (round.source == ForeignPerpPositionSource.EXCHANGE_API) {
            return state.copy(manualFundingUsdt = null, manualFundingChangedAt = null)
        }
        val current = round.fundingUsdt?.stripTrailingZeros()
        val stored = state.manualFundingUsdt
        val unchanged = state.manualFundingChangedAt != null && when {
            current == null || stored == null -> current == null && stored == null
            else -> current.compareTo(stored) == 0
        }
        return if (unchanged) state else state.copy(manualFundingUsdt = current, manualFundingChangedAt = round.observedAt)
    }

    private fun staleness(tracked: ReminderState, round: AccountRound, settings: AccountAlertSettings): ManualFundingStaleness? {
        if (round.source != ForeignPerpPositionSource.MANUAL_ENV) return null
        val funding = tracked.manualFundingUsdt ?: return null
        val changedAt = tracked.manualFundingChangedAt ?: return null
        val elapsed = Duration.between(changedAt, round.observedAt)
        if (elapsed < settings.manualFundingStaleAfter) return null
        return ManualFundingStaleness(funding, changedAt, elapsed.toDays())
    }
}
