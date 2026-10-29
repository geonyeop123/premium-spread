package io.premiumspread.domain.ownermarket

import io.premiumspread.domain.accountpnl.AccountPnlLatest
import io.premiumspread.domain.exchangerate.ExchangeRateSnapshot
import io.premiumspread.domain.market.MarketPair
import io.premiumspread.domain.premium.PremiumAggregationSnapshot
import io.premiumspread.domain.ticker.Currency
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * 읽기에 성공한 요약 입력. day null = 전일 bucket 없음, fx null = 환율 없음, pnl null = 최신값 행 없음.
 * fxBase·fxQuote = Job 이 batchMarket.fxBase·fxQuote 에서 채운다(notice 로 그대로 옮긴다).
 */
data class DailySummaryInput(
    val day: PremiumAggregationSnapshot?,
    val fxBase: Currency,
    val fxQuote: Currency,
    val fx: ExchangeRateSnapshot?,
    val pnl: AccountPnlLatest?,
)

object DailySummaryPolicy {
    /** 이번 회차가 요약을 보낼 차례인가. 입력 읽기 전에 부른다. key 는 검사하지 않는다. */
    fun due(state: MarketAlertState, settings: MarketAlertSettings, now: Instant, zone: ZoneId): Boolean {
        val local = now.atZone(zone)
        val summaryDate = state.summaryDate
        return local.hour >= settings.summaryHour && (summaryDate == null || summaryDate < local.toLocalDate())
    }

    /** due 가 참이고 입력 읽기가 모두 예외 없이 끝났을 때만 부른다. */
    fun decide(
        state: MarketAlertState,
        pair: MarketPair,
        input: DailySummaryInput,
        settings: MarketAlertSettings,
        now: Instant,
        zone: ZoneId,
    ): MarketDecision {
        MarketKeys.requireKey(state, MarketKeys.dailySummary(MarketKeys.pairKey(pair)))
        val today = now.atZone(zone).toLocalDate()
        val notice = MarketNotice.DailySummary(
            pair, now, today.minusDays(1), input.day, input.fxBase, input.fxQuote, input.fx,
            summaryPnl(input.pnl, settings, now),
        )
        return MarketDecision(listOf(state.copy(summaryDate = today, lastNotifiedAt = now)), listOf(notice))
    }

    /** 정확히 maxAge 는 유효. calculatedAt 이 미래면 나이 0. */
    fun summaryPnl(pnl: AccountPnlLatest?, settings: MarketAlertSettings, now: Instant): SummaryPnl = when {
        pnl == null -> SummaryPnl.Unavailable

        Duration.between(pnl.calculatedAt, now) > settings.summaryPnlMaxAge -> SummaryPnl.Unavailable

        !pnl.position -> SummaryPnl.NoPosition(pnl.calculatedAt)

        else -> SummaryPnl.Value(pnl.netPercent!!, pnl.netKrw!!, pnl.calculatedAt)
    }
}
