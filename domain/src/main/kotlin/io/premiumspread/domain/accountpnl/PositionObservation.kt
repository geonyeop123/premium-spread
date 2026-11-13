package io.premiumspread.domain.accountpnl

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

enum class PositionObservationKind { HOLDING, FLAT }

/** 매 성공 회차의 관측 한 줄. 실계정 값이므로 toString 은 kind·observedAt 만. */
@Suppress("LongParameterList")
data class PositionObservation(
    val kind: PositionObservationKind,
    val observedAt: Instant,
    val netPercent: BigDecimal?,
    val netKrw: BigDecimal?,
    val currentPremiumPercent: BigDecimal,
    val entryPremiumPercent: BigDecimal,
    val premiumGapPercentPoint: BigDecimal,
    val fxRate: BigDecimal,
    val koreaExitPrice: BigDecimal,
    val foreignExitPrice: BigDecimal,
) {
    init {
        require((kind == PositionObservationKind.HOLDING) == (netPercent != null && netKrw != null)) {
            "net values must be present exactly when kind is HOLDING."
        }
        require((netPercent == null) == (netKrw == null)) { "net values must be both present or both absent." }
    }

    override fun toString(): String = "PositionObservation(kind=$kind, observedAt=$observedAt)"

    companion object {
        /** 김프 3종은 [AccountPnlBreakdown.of] 값 그대로, netKrw 는 [AccountPnlLatest.computed] 와 같은 scale 0 HALF_UP. */
        fun holding(pnl: AccountNetPnl): PositionObservation {
            val breakdown = AccountPnlBreakdown.of(pnl)
            return PositionObservation(
                kind = PositionObservationKind.HOLDING,
                observedAt = pnl.calculatedAt,
                netPercent = pnl.netPercent,
                netKrw = pnl.netKrw.setScale(0, RoundingMode.HALF_UP),
                currentPremiumPercent = breakdown.currentPremiumPercent,
                entryPremiumPercent = breakdown.entryPremiumPercent,
                premiumGapPercentPoint = breakdown.premiumGapPercentPoint,
                fxRate = pnl.fxRate,
                koreaExitPrice = pnl.koreaExitPrice,
                foreignExitPrice = pnl.foreignExitPrice,
            )
        }

        /**
         * [holding] 과 같되 계산이 불가능한 입력(평단 0 등 → [ArithmeticException])이면 null — 관측 0행 신호다.
         * 건·사건 기록과 관측 계산 실패를 분리하기 위한 것이며 ArithmeticException 외 예외는 전파한다.
         */
        fun holdingOrNull(pnl: AccountNetPnl): PositionObservation? =
            try {
                holding(pnl)
            } catch (_: ArithmeticException) {
                null
            }

        /** 청산가는 reference 수량으로 소화한 VWAP(계정 수량 무관). 호가 깊이가 부족하거나 계산 불가(평단 0)면 null, 다른 예외는 전파한다. */
        fun flat(reference: ReferenceLegs, market: MarketQuote, observedAt: Instant): PositionObservation? {
            val fx = market.fxRate
            val koreaExit: BigDecimal
            val foreignExit: BigDecimal
            try {
                koreaExit = HedgeQuote.vwap(market.koreaBidLevels, reference.koreaQuantity, "bithumb bid")
                foreignExit = HedgeQuote.vwap(market.foreignAskLevels, reference.foreignQuantity, "binance ask")
            } catch (e: AccountReadException) {
                if (e.category == AccountReadFailureCategory.INSUFFICIENT_DEPTH) return null
                throw e
            }
            val current: BigDecimal
            val entry: BigDecimal
            try {
                current = HedgeQuote.premiumPercent(koreaExit, foreignExit, fx)
                entry = HedgeQuote.premiumPercent(reference.koreaAveragePrice, reference.foreignEntryPrice, fx)
            } catch (_: ArithmeticException) {
                return null // 평단 0 등 계산 불가 입력: 관측 0행
            }
            return PositionObservation(
                kind = PositionObservationKind.FLAT,
                observedAt = observedAt,
                netPercent = null,
                netKrw = null,
                currentPremiumPercent = current,
                entryPremiumPercent = entry,
                premiumGapPercentPoint = current.subtract(entry),
                fxRate = fx,
                koreaExitPrice = koreaExit,
                foreignExitPrice = foreignExit,
            )
        }
    }
}

/** 호가·환율 시세. 호가는 실계정 비의존 값이지만 toString 은 타입명만. */
data class MarketQuote(val koreaBidLevels: List<PriceLevel>, val foreignAskLevels: List<PriceLevel>, val fxRate: BigDecimal) {
    override fun toString(): String = "MarketQuote"
}

/** 이력 저장 입력 한 회차. [pnl] 은 헤지 성립·계산 성공 회차만 non-null. */
data class PositionHistoryRound(
    val legs: PositionLegs,
    val source: ForeignPerpPositionSource,
    val pnl: AccountNetPnl?,
    val market: MarketQuote,
    val observedAt: Instant,
    val legObservations: LegObservations = LegObservations.NONE,
) {
    init {
        require(source != ForeignPerpPositionSource.MANUAL_ENV || legObservations.foreign == null) {
            "수동 모드 회차는 바이낸스 레그 관측을 가질 수 없다"
        }
    }

    override fun toString(): String = "PositionHistoryRound(observedAt=$observedAt)"
}
