package io.premiumspread.domain.accountpnl

import java.math.BigDecimal
import java.math.RoundingMode

/** 기준 포지션 단계 판정의 국면 — 지금 포지션을 들고 있는가(HOLDING), 직전 포지션 기준으로 보는가(FLAT). */
enum class ReferencePhase { HOLDING, FLAT }

/**
 * 기준 포지션 단계 상태 (#131 design D2·D4). 셋 다 null(상태 없음) 이거나 셋 다 non-null 이다.
 * 실계정 값이므로 toString 은 phase·level 만.
 */
data class ReferenceStepState(val reference: ReferenceLegs?, val phase: ReferencePhase?, val level: Int?) {
    init {
        require((reference == null) == (phase == null) && (phase == null) == (level == null)) {
            "reference step state must be fully empty or fully set."
        }
    }

    override fun toString(): String = "ReferenceStepState(phase=$phase, level=$level)"

    companion object {
        val INITIAL = ReferenceStepState(null, null, null)
    }
}

/** 판정에 쓸 한 회차의 기준·관측. 판정 값은 실시간 환율 기준 [PositionObservation.premiumGapPercentPoint]. */
data class ReferenceGap(val phase: ReferencePhase, val reference: ReferenceLegs, val observation: PositionObservation) {
    val value: BigDecimal get() = observation.premiumGapPercentPoint

    override fun toString(): String = "ReferenceGap(phase=$phase, observation=$observation)"

    companion object {
        /** FLAT 국면(정규화 leg 완전 flat)인데 state.reference == null 이면 true — Job 이 부트스트랩 조회를 할지 결정한다. */
        fun needsBootstrap(round: PositionHistoryRound, state: ReferenceStepState): Boolean =
            round.pnl == null && isFlat(round) && state.reference == null

        /** design D2 표. 판정 안 하는 회차는 null. [bootstrap] 은 state.reference 가 없을 때만 쓴다. */
        fun of(round: PositionHistoryRound, state: ReferenceStepState, bootstrap: ReferenceLegs?): ReferenceGap? {
            val pnl = round.pnl
            return when {
                pnl != null -> holding(pnl)
                isFlat(round) -> (state.reference ?: bootstrap)?.let { flat(round, it) }
                else -> null
            }
        }

        /** 헤지 성립 회차: 기준 = 현재 leg(바이낸스 수량은 절댓값). 관측 계산 불가면 null. */
        private fun holding(pnl: AccountNetPnl): ReferenceGap? {
            val position = pnl.position
            val reference = ReferenceLegs(
                koreaQuantity = position.korea.quantity,
                koreaAveragePrice = position.korea.averageBuyPrice,
                foreignQuantity = position.foreign.signedQuantity.abs(),
                foreignEntryPrice = position.foreign.entryPrice,
            )
            return PositionObservation.holdingOrNull(pnl)?.let { ReferenceGap(ReferencePhase.HOLDING, reference, it) }
        }

        /** 완전 flat 회차: 기준 수량으로 현재 호가 VWAP. 호가 깊이 부족·계산 불가면 null. */
        private fun flat(round: PositionHistoryRound, reference: ReferenceLegs): ReferenceGap? =
            PositionObservation.flat(reference, round.market, round.observedAt)
                ?.let { ReferenceGap(ReferencePhase.FLAT, reference, it) }

        private fun isFlat(round: PositionHistoryRound): Boolean {
            val legs = PositionEpisodePolicy.normalize(round.legs)
            return legs.korea.isEmpty && legs.foreign.isFlat
        }
    }
}

data class ReferenceStepDecision(val next: ReferenceStepState, val alert: AccountAlert.ReferenceStep?)

/** 기준 포지션 대비 김프 차이의 단계 판정 (#131 design D4·D5). 순수 함수. */
object ReferenceStepPolicy {
    fun decide(state: ReferenceStepState, gap: ReferenceGap?, stepWidth: BigDecimal): ReferenceStepDecision {
        if (gap == null) return ReferenceStepDecision(state, null)

        val value = gap.value
        if (isTransition(state, gap)) {
            return ReferenceStepDecision(next(gap, levelOf(value, stepWidth, RoundingMode.DOWN)), null)
        }

        val current = requireNotNull(state.level) { "level is required when a reference exists." }
        val up = stepWidth.multiply(BigDecimal(current + 1))
        val down = stepWidth.multiply(BigDecimal(current - 1))
        return when {
            value >= up -> moved(SwingDirection.UP, levelOf(value, stepWidth, RoundingMode.FLOOR), stepWidth, gap)
            value <= down -> moved(SwingDirection.DOWN, levelOf(value, stepWidth, RoundingMode.CEILING), stepWidth, gap)
            else -> ReferenceStepDecision(next(gap, current), null)
        }
    }

    /** 상태 없음 / 무포지션→보유 / 보유 중 평단 변경이면 기준을 새로 잡는다(알림 없음). 수량만 바뀐 것은 이어간다. */
    private fun isTransition(state: ReferenceStepState, gap: ReferenceGap): Boolean {
        val before = state.reference ?: return true
        if (state.phase == ReferencePhase.FLAT && gap.phase == ReferencePhase.HOLDING) return true
        if (gap.phase != ReferencePhase.HOLDING) return false
        return before.koreaAveragePrice.compareTo(gap.reference.koreaAveragePrice) != 0 ||
            before.foreignEntryPrice.compareTo(gap.reference.foreignEntryPrice) != 0
    }

    private fun moved(direction: SwingDirection, level: Int, stepWidth: BigDecimal, gap: ReferenceGap) =
        ReferenceStepDecision(
            next(gap, level),
            AccountAlert.ReferenceStep(direction, level, stepWidth, gap.phase, gap.observation),
        )

    private fun next(gap: ReferenceGap, level: Int) = ReferenceStepState(gap.reference, gap.phase, level)

    private fun levelOf(value: BigDecimal, stepWidth: BigDecimal, mode: RoundingMode): Int =
        value.divide(stepWidth, 0, mode).intValueExact()
}
