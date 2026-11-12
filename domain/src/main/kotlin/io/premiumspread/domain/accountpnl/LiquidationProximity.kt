package io.premiumspread.domain.accountpnl

import java.math.BigDecimal
import java.math.RoundingMode

/** 판정한 바이낸스 선물 포지션의 방향 — signedQuantity 음수 = SHORT, 양수 = LONG. */
enum class PerpSide { SHORT, LONG }

/**
 * 청산 근접 단계 상태 (#141 design D5). 셋 다 null(INITIAL) 이거나 셋 다 non-null 이다.
 * [entryPrice] = 마지막 판정 회차의 진입가(비교는 compareTo), [level] = 마지막으로 알린 경계(0 = 없음).
 * 실계정 값이므로 toString 은 side·level 만.
 */
data class LiquidationProximityState(val side: PerpSide?, val entryPrice: BigDecimal?, val level: Int?) {
    init {
        require((side == null) == (entryPrice == null) && (entryPrice == null) == (level == null)) {
            "liquidation proximity state must be fully empty or fully set."
        }
        require(level == null || level == 0 || level in LiquidationProximityPolicy.BOUNDARIES) {
            "level must be 0 or a liquidation boundary."
        }
    }

    override fun toString(): String = "LiquidationProximityState(side=$side, level=$level)"

    companion object {
        val INITIAL = LiquidationProximityState(null, null, null)
    }
}

/** 한 회차 판정 결과. [alert] 는 [AccountAlert.LiquidationProximity]·[AccountAlert.LiquidationEased] 중 하나이거나 null. */
data class LiquidationProximityDecision(val next: LiquidationProximityState, val alert: AccountAlert?) {
    init {
        require(alert == null || alert is AccountAlert.LiquidationProximity || alert is AccountAlert.LiquidationEased) {
            "liquidation decision carries only a liquidation alert."
        }
    }
}

/**
 * 진입가→청산가 진행률의 단계 판정 (#141 design D1~D5). 순수 함수.
 * 진행률이 경계를 위로 처음 넘으면 그 경계 하나를 알리고, 알린 경계보다 한 단계 아래 경계 밑으로 내려가면
 * 재무장하며 완화 알림 1건을 낸다. 한 회차에 알림은 많아야 1건이다.
 */
object LiquidationProximityPolicy {
    val BOUNDARIES: List<Int> = listOf(50, 60, 70, 80, 90)
    const val REARM_WIDTH: Int = 10

    private const val PROGRESS_SCALE = 4
    private val HUNDRED = BigDecimal(100)

    /**
     * 판정하지 않는 회차(design D2)는 상태를 그대로 둔다. 실계정 회차에서 바이낸스 leg 가 flat 이면 INITIAL(D5).
     */
    fun decide(state: LiquidationProximityState, round: AccountRound?): LiquidationProximityDecision {
        val unchanged = LiquidationProximityDecision(state, null)
        if (round == null || round.source != ForeignPerpPositionSource.EXCHANGE_API) return unchanged
        if (round.legs.foreign.isFlat) return LiquidationProximityDecision(LiquidationProximityState.INITIAL, null)

        val reading = read(round) ?: return unchanged
        return judge(state, reading)
    }

    /** 판정할 값. 위험 지표·청산가가 없거나 청산가가 진입가와 같거나 반대쪽이면 null(design D2). */
    private fun read(round: AccountRound): Reading? {
        val foreign = round.legs.foreign
        val risk = round.foreignRisk ?: return null
        val liquidation = risk.liquidationPrice ?: return null
        val side = if (foreign.signedQuantity.signum() < 0) PerpSide.SHORT else PerpSide.LONG
        if (!isLiquidationBeyondEntry(side, foreign.entryPrice, liquidation)) return null

        val progress = requireNotNull(progress(foreign.entryPrice, risk.markPrice, liquidation)) {
            "progress is defined when liquidation differs from entry."
        }
        return Reading(side, foreign.entryPrice, risk.markPrice, liquidation, progress, round)
    }

    /** `(mark − entry) / (liq − entry) × 100`, scale 4 · 0 쪽 절사(design D1). liq == entry 면 null. */
    fun progress(entry: BigDecimal, mark: BigDecimal, liquidation: BigDecimal): BigDecimal? {
        val distance = liquidation.subtract(entry)
        if (distance.signum() == 0) return null
        return mark.subtract(entry).multiply(HUNDRED).divide(distance, PROGRESS_SCALE, RoundingMode.DOWN)
    }

    /** 숏은 청산가가 진입가 위, 롱은 아래여야 판정한다(design D2 마지막 행). */
    private fun isLiquidationBeyondEntry(side: PerpSide, entry: BigDecimal, liquidation: BigDecimal): Boolean {
        val direction = liquidation.compareTo(entry)
        return if (side == PerpSide.SHORT) direction > 0 else direction < 0
    }

    /** design D3 재무장·상승, D4 완화, D5 방향·진입가 변경. */
    private fun judge(state: LiquidationProximityState, reading: Reading): LiquidationProximityDecision {
        val current = levelOf(reading.progress)
        val carried = carriedLevel(state, reading, current)
        val armed = minOf(carried, levelOf(reading.progress.add(BigDecimal(REARM_WIDTH))))
        val eased = armed < carried

        if (current > armed) {
            check(!eased) { "an eased round cannot raise a liquidation boundary." }
            return LiquidationProximityDecision(reading.state(current), reading.proximity(current))
        }
        val alert = if (eased) reading.eased(carried, armed) else null
        return LiquidationProximityDecision(reading.state(armed), alert)
    }

    /**
     * 판정 전 level. INITIAL·방향 변경이면 0, 진입가가 바뀌었으면 현재 경계를 한 번 다시 알릴 수 있게 한 단계 아래로 내린다.
     * 이 두 경우는 완화 알림 대상이 아니다(design D4) — 내린 level 은 재무장 단계에서 다시 줄지 않는다.
     */
    private fun carriedLevel(state: LiquidationProximityState, reading: Reading, current: Int): Int {
        val stored = state.level ?: return 0
        if (state.side != reading.side) return 0
        val storedEntry = requireNotNull(state.entryPrice) { "entryPrice is required when a level exists." }
        if (storedEntry.compareTo(reading.entry) == 0) return stored
        return minOf(stored, levelOf(BigDecimal(current - REARM_WIDTH)))
    }

    /** [value] 이하인 가장 높은 경계. 50 미만이면 0. */
    private fun levelOf(value: BigDecimal): Int = BOUNDARIES.lastOrNull { BigDecimal(it) <= value } ?: 0

    /** 판정 회차의 값. 실계정 가격이므로 toString 은 side 만. */
    private data class Reading(
        val side: PerpSide,
        val entry: BigDecimal,
        val mark: BigDecimal,
        val liquidation: BigDecimal,
        val progress: BigDecimal,
        val round: AccountRound,
    ) {
        fun state(level: Int) = LiquidationProximityState(side, entry, level)

        fun proximity(level: Int) = AccountAlert.LiquidationProximity(
            level = level,
            progressPercent = progress,
            side = side,
            entryPrice = entry,
            markPrice = mark,
            liquidationPrice = liquidation,
            config = round.foreignConfig,
            observedAt = round.observedAt,
        )

        fun eased(previousLevel: Int, armedLevel: Int) = AccountAlert.LiquidationEased(
            previousLevel = previousLevel,
            armedLevel = armedLevel,
            progressPercent = progress,
            side = side,
            entryPrice = entry,
            markPrice = mark,
            liquidationPrice = liquidation,
            config = round.foreignConfig,
            observedAt = round.observedAt,
        )

        override fun toString(): String = "Reading(side=$side)"
    }
}
