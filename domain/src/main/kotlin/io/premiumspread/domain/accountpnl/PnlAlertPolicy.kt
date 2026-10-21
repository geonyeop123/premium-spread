package io.premiumspread.domain.accountpnl

import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

/** 수익 알림 에피소드 상태 (design §4.1). 에피소드 밖에서는 [positionFingerprint] 와 [lastSentAt] 이 null 이다. */
data class PnlAlertState(val inEpisode: Boolean, val positionFingerprint: String?, val lastSentAt: Instant?) {
    companion object {
        val INITIAL = PnlAlertState(false, null, null)
    }
}

sealed interface PnlObservation {
    data class Computed(val pnl: AccountNetPnl) : PnlObservation
    data object NoPosition : PnlObservation
    data object Failed : PnlObservation
}

data class PnlAlertDecision(val next: PnlAlertState, val send: Boolean)

object PnlAlertPolicy {
    val TRIGGER: BigDecimal = BigDecimal("1.00")
    val REARM_BELOW: BigDecimal = BigDecimal("0.90")
    val RESEND_INTERVAL: Duration = Duration.ofMinutes(60)

    fun decide(state: PnlAlertState, observation: PnlObservation, now: Instant): PnlAlertDecision =
        when (observation) {
            PnlObservation.Failed -> PnlAlertDecision(state, false)
            PnlObservation.NoPosition -> PnlAlertDecision(PnlAlertState.INITIAL, false)
            is PnlObservation.Computed -> decideComputed(state, observation.pnl, now)
        }

    private fun decideComputed(state: PnlAlertState, pnl: AccountNetPnl, now: Instant): PnlAlertDecision {
        val fingerprint = pnl.position.fingerprint
        // 포지션이 바뀌면 이전 에피소드를 끝내고 같은 관측을 에피소드 밖에서 다시 판정한다
        val current = if (state.inEpisode && state.positionFingerprint != fingerprint) PnlAlertState.INITIAL else state
        val percent = pnl.netPercent

        return if (!current.inEpisode) {
            if (percent >= TRIGGER) {
                PnlAlertDecision(PnlAlertState(true, fingerprint, now), true)
            } else {
                PnlAlertDecision(PnlAlertState.INITIAL, false)
            }
        } else if (percent < REARM_BELOW) {
            PnlAlertDecision(PnlAlertState.INITIAL, false)
        } else if (percent >= TRIGGER && resendDue(current.lastSentAt, now)) {
            PnlAlertDecision(current.copy(lastSentAt = now), true)
        } else {
            PnlAlertDecision(current, false)
        }
    }

    private fun resendDue(lastSentAt: Instant?, now: Instant): Boolean =
        lastSentAt == null || Duration.between(lastSentAt, now) >= RESEND_INTERVAL
}
