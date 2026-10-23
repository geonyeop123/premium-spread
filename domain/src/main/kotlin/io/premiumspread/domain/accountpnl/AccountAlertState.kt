package io.premiumspread.domain.accountpnl

import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

/** 빗썸 현물 leg (design §3.1). [of] 로 만든다 — dust 는 [EMPTY], 값은 stripTrailingZeros. 비교는 [samePosition]. */
data class KoreaLeg(val quantity: BigDecimal, val averageBuyPrice: BigDecimal) {
    val isEmpty: Boolean get() = quantity.signum() == 0

    /** 두 필드 모두 compareTo == 0 (scale 무관). */
    fun samePosition(other: KoreaLeg): Boolean =
        quantity.compareTo(other.quantity) == 0 && averageBuyPrice.compareTo(other.averageBuyPrice) == 0

    companion object {
        val EMPTY: KoreaLeg = KoreaLeg(BigDecimal.ZERO, BigDecimal.ZERO)

        fun of(snapshot: KoreaSpotAccountSnapshot): KoreaLeg = of(snapshot.quantity, snapshot.averageBuyPrice)

        /** quantity < [AccountHedgePosition.KOREA_DUST] → [EMPTY]. 저장소 복원도 이 함수를 쓴다. */
        fun of(quantity: BigDecimal, averageBuyPrice: BigDecimal): KoreaLeg =
            if (quantity < AccountHedgePosition.KOREA_DUST) {
                EMPTY
            } else {
                KoreaLeg(quantity.stripTrailingZeros(), averageBuyPrice.stripTrailingZeros())
            }
    }
}

/** 바이낸스 선물 leg. signedQuantity == 0 이면 [FLAT](진입가 무시). */
data class ForeignLeg(val signedQuantity: BigDecimal, val entryPrice: BigDecimal) {
    val isFlat: Boolean get() = signedQuantity.signum() == 0

    fun samePosition(other: ForeignLeg): Boolean =
        signedQuantity.compareTo(other.signedQuantity) == 0 && entryPrice.compareTo(other.entryPrice) == 0

    companion object {
        val FLAT: ForeignLeg = ForeignLeg(BigDecimal.ZERO, BigDecimal.ZERO)

        fun of(snapshot: ForeignPerpAccountSnapshot): ForeignLeg = of(snapshot.signedQuantity, snapshot.entryPrice)

        fun of(signedQuantity: BigDecimal, entryPrice: BigDecimal): ForeignLeg =
            if (signedQuantity.signum() == 0) {
                FLAT
            } else {
                ForeignLeg(signedQuantity.stripTrailingZeros(), entryPrice.stripTrailingZeros())
            }
    }
}

data class PositionLegs(val korea: KoreaLeg, val foreign: ForeignLeg) {
    companion object {
        fun of(korea: KoreaSpotAccountSnapshot, foreign: ForeignPerpAccountSnapshot): PositionLegs =
            PositionLegs(KoreaLeg.of(korea), ForeignLeg.of(foreign))
    }
}

/** 포지션 변경 감지 상태 (design §3.2). legs == null = 아직 기록 없음. */
data class PositionWatchState(val legs: PositionLegs?, val hedgeMismatchWarned: Boolean) {
    companion object {
        val INITIAL = PositionWatchState(null, false)
    }
}

/** 순수익률 급변 상태 (design §3.3). bucketStart == null 이면 나머지는 null/false/false. */
data class SwingState(
    val bucketStart: Instant?,
    val baselinePercent: BigDecimal?,
    val upSent: Boolean,
    val downSent: Boolean,
) {
    init {
        require(bucketStart != null || (baselinePercent == null && !upSent && !downSent)) {
            "swing state without a bucket must be empty."
        }
    }

    companion object {
        val INITIAL = SwingState(null, null, false, false)
    }
}

/**
 * 미반영 리마인더 상태 (design §3.4). [lastDayStart] = 리마인더를 마지막으로 **보낸** zone 날짜의 자정.
 * manualFundingChangedAt == null 이면 manualFundingUsdt == null.
 */
data class ReminderState(
    val lastDayStart: Instant?,
    val manualFundingUsdt: BigDecimal?,
    val manualFundingChangedAt: Instant?,
) {
    init {
        require(manualFundingChangedAt != null || manualFundingUsdt == null) {
            "manual funding value requires its change time."
        }
    }

    companion object {
        val INITIAL = ReminderState(null, null, null)
    }
}

data class AccountAlertState(val position: PositionWatchState, val swing: SwingState, val reminder: ReminderState) {
    companion object {
        val INITIAL = AccountAlertState(PositionWatchState.INITIAL, SwingState.INITIAL, ReminderState.INITIAL)
    }
}

data class AccountAlertSettings(
    val swingThreshold: BigDecimal = BigDecimal("0.50"),
    val reminderHour: Int = 9,
    val manualFundingStaleAfter: Duration = Duration.ofDays(3),
) {
    init {
        require(swingThreshold.signum() > 0) { "swingThreshold must be positive." }
        require(reminderHour in 0..23) { "reminderHour must be between 0 and 23." }
        require(!manualFundingStaleAfter.isNegative && !manualFundingStaleAfter.isZero) {
            "manualFundingStaleAfter must be positive."
        }
    }
}

/**
 * 성공 회차 1개의 관측 (design §3.5). 실패 회차는 만들지 않는다(null 로 넘긴다).
 * [pnl] == null 은 포지션 없음. [observedAt] 은 두 계정 조회를 마친 시각이며 pnl 이 있으면 pnl.calculatedAt 과 같다.
 */
data class AccountRound(
    val legs: PositionLegs,
    val source: ForeignPerpPositionSource,
    val fundingUsdt: BigDecimal?,
    val pnl: AccountNetPnl?,
    val observedAt: Instant,
) {
    init {
        require(pnl == null || pnl.calculatedAt == observedAt) { "pnl must be calculated at observedAt." }
    }
}
