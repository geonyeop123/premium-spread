package io.premiumspread.domain.accountpnl

import java.time.Instant

data class PositionChangeDecision(val next: PositionWatchState, val alert: AccountAlert.PositionChanged?)

object PositionChangePolicy {
    /** design §3.2 전이표 P1~P8. [now] 는 감지 시각(회차 observedAt). */
    fun decide(
        state: PositionWatchState,
        legs: PositionLegs,
        source: ForeignPerpPositionSource,
        now: Instant,
    ): PositionChangeDecision {
        val before = state.legs ?: return PositionChangeDecision(PositionWatchState(legs, false), null)

        val koreaChanged = !before.korea.samePosition(legs.korea)
        val foreignChanged = !before.foreign.samePosition(legs.foreign)
        val manual = source == ForeignPerpPositionSource.MANUAL_ENV

        if (!koreaChanged && !foreignChanged) {
            val warned = manual && state.hedgeMismatchWarned
            return PositionChangeDecision(PositionWatchState(legs, warned), null)
        }

        val koreaOnlyManual = koreaChanged && !foreignChanged && manual
        val warning = koreaOnlyManual && !state.hedgeMismatchWarned
        val nextWarned = koreaOnlyManual && (state.hedgeMismatchWarned || warning)
        val alert = AccountAlert.PositionChanged(before, legs, warning, source, now)
        return PositionChangeDecision(PositionWatchState(legs, nextWarned), alert)
    }
}
