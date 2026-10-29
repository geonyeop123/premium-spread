package io.premiumspread.domain.ownermarket

import io.premiumspread.domain.exchangerate.ExchangeRateSnapshot
import io.premiumspread.domain.market.MarketPair
import io.premiumspread.domain.owneralert.OwnerAlertKind
import io.premiumspread.domain.premium.PremiumAggregationSnapshot
import io.premiumspread.domain.ticker.Currency
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

enum class MarketDirection { UP, DOWN }

enum class ExtremeWindow(val duration: Duration) {
    ONE_HOUR(Duration.ofHours(1)),
    ONE_DAY(Duration.ofHours(24)),
}

enum class ThresholdLine { UPPER, LOWER }

/** 이번 회차에 보낼 시장 알림. [kind] 가 라우팅 종류. */
sealed interface MarketNotice {
    val pair: MarketPair
    val at: Instant
    val kind: OwnerAlertKind

    /** reference = 창 안 분봉 종가 저점(UP)·고점(DOWN). delta = current - reference (UP 양수, DOWN 음수). */
    data class Swing(
        override val pair: MarketPair,
        override val at: Instant,
        val direction: MarketDirection,
        val window: Duration,
        val reference: BigDecimal,
        val current: BigDecimal,
        val currentObservedAt: Instant,
        val delta: BigDecimal,
    ) : MarketNotice {
        override val kind: OwnerAlertKind get() = OwnerAlertKind.PREMIUM_SWING
    }

    /** threshold = 넘은 기준선 값. */
    data class ThresholdCrossed(
        override val pair: MarketPair,
        override val at: Instant,
        val line: ThresholdLine,
        val threshold: BigDecimal,
        val hysteresis: BigDecimal,
        val current: BigDecimal,
        val currentObservedAt: Instant,
    ) : MarketNotice {
        override val kind: OwnerAlertKind get() = OwnerAlertKind.PREMIUM_THRESHOLD_CROSSED
    }

    /** previous = 창의 직전 고점(UP)·저점(DOWN). */
    data class NewExtreme(
        override val pair: MarketPair,
        override val at: Instant,
        val window: ExtremeWindow,
        val direction: MarketDirection,
        val previous: BigDecimal,
        val current: BigDecimal,
        val currentObservedAt: Instant,
    ) : MarketNotice {
        override val kind: OwnerAlertKind get() = OwnerAlertKind.PREMIUM_NEW_EXTREME
    }

    /**
     * date = 대상일(전일). day null = 전일 집계 없음, fx null = 현재 환율 없음.
     * fxBase·fxQuote = 환율 통화쌍 표기 재료 - fx 가 null 이어도 라벨을 낸다.
     */
    data class DailySummary(
        override val pair: MarketPair,
        override val at: Instant,
        val date: LocalDate,
        val day: PremiumAggregationSnapshot?,
        val fxBase: Currency,
        val fxQuote: Currency,
        val fx: ExchangeRateSnapshot?,
        val pnl: SummaryPnl,
    ) : MarketNotice {
        override val kind: OwnerAlertKind get() = OwnerAlertKind.DAILY_SUMMARY
    }
}

/** 요약의 실계정 줄. */
sealed interface SummaryPnl {
    /** 금액은 실계정 값이므로 toString 에 넣지 않는다. */
    data class Value(val netPercent: BigDecimal, val netKrw: BigDecimal, val calculatedAt: Instant) : SummaryPnl {
        override fun toString(): String = "Value(calculatedAt=$calculatedAt)"
    }

    data class NoPosition(val calculatedAt: Instant) : SummaryPnl

    /** 모니터 꺼짐·기록 없음·오래됨. */
    data object Unavailable : SummaryPnl
}

/** 정책 결과. next = 쓸 상태(빈 목록 = 쓰지 않음). */
data class MarketDecision(val next: List<MarketAlertState>, val notices: List<MarketNotice>) {
    companion object {
        val NONE: MarketDecision = MarketDecision(emptyList(), emptyList())
    }
}
