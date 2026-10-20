package io.premiumspread.domain.accountpnl

import java.time.Duration
import java.time.Instant

/** 모니터링 중단 알림 상태 (design §4.2). */
data class OutageState(
    val lastSuccessAt: Instant?,
    val monitoringSince: Instant?,
    val outageNotified: Boolean,
) {
    companion object {
        val INITIAL = OutageState(null, null, false)
    }
}

sealed interface OutageNotice {
    /** 첫 실패 범주가 아니라 5분 초과를 판정한 회차의 범주를 싣는다. */
    data class Outage(val category: AccountReadFailureCategory, val lastSuccessAt: Instant?) : OutageNotice

    data object Recovered : OutageNotice
}

data class OutageDecision(val next: OutageState, val notice: OutageNotice?)

object MonitoringOutagePolicy {
    val THRESHOLD: Duration = Duration.ofMinutes(5)

    /** [failure] 가 null 이면 성공 회차다. 포지션 없음도 성공이다. */
    fun decide(state: OutageState, failure: AccountReadFailureCategory?, now: Instant): OutageDecision {
        val since = state.monitoringSince ?: now
        val seeded = state.copy(monitoringSince = since)

        if (failure == null) {
            val notice = if (state.outageNotified) OutageNotice.Recovered else null
            return OutageDecision(OutageState(now, since, false), notice)
        }

        val reference = state.lastSuccessAt ?: since
        return if (!state.outageNotified && Duration.between(reference, now) > THRESHOLD) {
            OutageDecision(seeded.copy(outageNotified = true), OutageNotice.Outage(failure, state.lastSuccessAt))
        } else {
            OutageDecision(seeded, null)
        }
    }
}
