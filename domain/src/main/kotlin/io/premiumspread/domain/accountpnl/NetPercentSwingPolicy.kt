package io.premiumspread.domain.accountpnl

import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

data class SwingDecision(val next: SwingState, val alert: AccountAlert.NetPercentSwing?)

/** 순수익률 정시 기준선 급변 판정 (design §3.3 S1~S6). */
object NetPercentSwingPolicy {
    /** zone 정시 버킷 시작 — AccountPnlMailContent.hourly 와 같은 식. 범위 [bucketStart, +1h). */
    fun bucketStart(at: Instant, zone: ZoneId): Instant = at.atZone(zone).truncatedTo(ChronoUnit.HOURS).toInstant()

    /** [pnl] == null 은 포지션 없음. 비교는 compareTo, 경계 포함. */
    fun decide(
        state: SwingState,
        bucket: Instant,
        pnl: AccountNetPnl?,
        positionChanged: Boolean,
        threshold: BigDecimal,
    ): SwingDecision {
        if (positionChanged) return SwingDecision(SwingState(bucket, null, false, false), null)
        if (state.bucketStart != bucket) {
            return SwingDecision(SwingState(bucket, pnl?.netPercent, false, false), null)
        }
        val baseline = state.baselinePercent
        if (baseline == null || pnl == null) return SwingDecision(state, null)

        val delta = pnl.netPercent.subtract(baseline)
        return when {
            delta >= threshold && !state.upSent ->
                SwingDecision(state.copy(upSent = true), alert(SwingDirection.UP, bucket, baseline, threshold, pnl))
            delta <= threshold.negate() && !state.downSent ->
                SwingDecision(state.copy(downSent = true), alert(SwingDirection.DOWN, bucket, baseline, threshold, pnl))
            else -> SwingDecision(state, null)
        }
    }

    private fun alert(
        direction: SwingDirection,
        bucket: Instant,
        baseline: BigDecimal,
        threshold: BigDecimal,
        pnl: AccountNetPnl,
    ) = AccountAlert.NetPercentSwing(direction, bucket, baseline, threshold, pnl)
}
