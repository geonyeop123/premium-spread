package io.premiumspread.domain.ownermarket

import io.premiumspread.domain.accountpnl.AccountPnlFormat
import io.premiumspread.domain.accountpnl.AccountPnlMail
import io.premiumspread.domain.market.MarketPair
import io.premiumspread.domain.owneralert.DiscordMessage
import io.premiumspread.domain.ownerops.OwnerOpsAlertContent
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId

/** owner 시장 알림 본문 (design §8). 메일(EMAIL 행)과 Discord 메시지를 같은 문구로 만든다. */
object OwnerMarketAlertContent {
    const val RUNBOOK = "docs/runbooks/owner-market-alert.md"
    const val UP_COLOR = 0xE74C3C
    const val DOWN_COLOR = 0x3498DB
    const val SUMMARY_COLOR = 0x95A5A6

    /** design §8.1. */
    fun eventKey(notice: MarketNotice): String {
        val base = "owner-market.${MarketKeys.pairKey(notice.pair)}"
        return when (notice) {
            is MarketNotice.Swing ->
                "$base.swing-${notice.direction.name.lowercase()}.${notice.at.toEpochMilli()}"

            is MarketNotice.ThresholdCrossed ->
                "$base.threshold-${notice.line.name.lowercase()}.${notice.at.toEpochMilli()}"

            is MarketNotice.NewExtreme -> {
                val window = if (notice.window == ExtremeWindow.ONE_HOUR) "1h" else "24h"
                val side = if (notice.direction == MarketDirection.UP) "high" else "low"
                "$base.extreme-$window-$side.${notice.at.toEpochMilli()}"
            }

            is MarketNotice.DailySummary -> "$base.daily-summary.${notice.date}"
        }
    }

    /** EMAIL 행(channel 기본값). 제목 = `[시장] ` + 핵심, 본문 = design §8.4. */
    fun mail(notice: MarketNotice, settings: MarketAlertSettings, zone: ZoneId): AccountPnlMail =
        AccountPnlMail(
            eventKey(notice),
            "[시장] " + titleCore(notice),
            body(notice, settings, zone) { AccountPnlFormat.time(it, zone) },
        )

    /** DiscordMessage.of 로 절단. 제목 = 이모지 + 핵심, 설명의 시각은 짧은 표기. */
    fun discord(notice: MarketNotice, settings: MarketAlertSettings, zone: ZoneId): DiscordMessage =
        DiscordMessage.of(
            "${emoji(notice)} ${titleCore(notice)}",
            body(notice, settings, zone) { AccountPnlFormat.shortTime(it, zone) },
            color(notice),
        )

    // 유니코드 escape: 📈 📉 🔺 🔻 ⬆️ ⬇️ 🗓️ (variation selector 포함)
    private const val CHART_UP = "\uD83D\uDCC8"
    private const val CHART_DOWN = "\uD83D\uDCC9"
    private const val TRIANGLE_UP = "\uD83D\uDD3A"
    private const val TRIANGLE_DOWN = "\uD83D\uDD3B"
    private const val ARROW_UP = "\u2B06\uFE0F"
    private const val ARROW_DOWN = "\u2B07\uFE0F"
    private const val CALENDAR = "\uD83D\uDDD3\uFE0F"

    private fun emoji(notice: MarketNotice): String = when (notice) {
        is MarketNotice.Swing -> if (notice.direction == MarketDirection.UP) CHART_UP else CHART_DOWN
        is MarketNotice.ThresholdCrossed -> if (notice.line == ThresholdLine.UPPER) TRIANGLE_UP else TRIANGLE_DOWN
        is MarketNotice.NewExtreme -> if (notice.direction == MarketDirection.UP) ARROW_UP else ARROW_DOWN
        is MarketNotice.DailySummary -> CALENDAR
    }

    private fun color(notice: MarketNotice): Int = when (notice) {
        is MarketNotice.Swing -> directionColor(notice.direction)
        is MarketNotice.ThresholdCrossed -> if (notice.line == ThresholdLine.UPPER) UP_COLOR else DOWN_COLOR
        is MarketNotice.NewExtreme -> directionColor(notice.direction)
        is MarketNotice.DailySummary -> SUMMARY_COLOR
    }

    private fun directionColor(direction: MarketDirection): Int =
        if (direction == MarketDirection.UP) UP_COLOR else DOWN_COLOR

    /** `{p}` 예 `BTC BITHUMB/BINANCE`. */
    private fun label(pair: MarketPair): String =
        "${pair.symbol.code} ${pair.koreaExchange.name}/${pair.foreignExchange.name}"

    private fun titleCore(notice: MarketNotice): String {
        val p = label(notice.pair)
        return when (notice) {
            is MarketNotice.Swing -> {
                val word = if (notice.direction == MarketDirection.UP) "급등" else "급락"
                "김프 $word $p ${pp(notice.delta)} (${OwnerOpsAlertContent.threshold(notice.window)})"
            }

            is MarketNotice.ThresholdCrossed ->
                if (notice.line == ThresholdLine.UPPER) {
                    "김프 상단 기준선 돌파 $p ${r(notice.current)} ≥ ${r(notice.threshold)}"
                } else {
                    "김프 하단 기준선 이탈 $p ${r(notice.current)} ≤ ${r(notice.threshold)}"
                }

            is MarketNotice.NewExtreme -> {
                val word = if (notice.direction == MarketDirection.UP) "신고가" else "신저가"
                "김프 ${windowLabel(notice.window)} $word $p ${r(notice.current)}"
            }

            is MarketNotice.DailySummary -> "김프 일일 요약 $p ${notice.date}"
        }
    }

    private fun windowLabel(window: ExtremeWindow): String =
        if (window == ExtremeWindow.ONE_HOUR) "1시간" else "24시간"

    private fun body(
        notice: MarketNotice,
        settings: MarketAlertSettings,
        zone: ZoneId,
        t: (Instant) -> String,
    ): String {
        val lines = when (notice) {
            is MarketNotice.Swing -> swingLines(notice, settings, t)
            is MarketNotice.ThresholdCrossed -> thresholdLines(notice, t)
            is MarketNotice.NewExtreme -> extremeLines(notice, settings, t)
            is MarketNotice.DailySummary -> summaryLines(notice, settings, zone, t)
        }
        return (lines + "조치 안내: $RUNBOOK").joinToString("\n")
    }

    private fun swingLines(notice: MarketNotice.Swing, settings: MarketAlertSettings, t: (Instant) -> String): List<String> {
        val window = OwnerOpsAlertContent.threshold(notice.window)
        val extreme = if (notice.direction == MarketDirection.UP) "저점" else "고점"
        return listOf(
            "현재 김프: ${r(notice.current)} (${t(notice.currentObservedAt)})",
            "최근 $window 분봉 종가 $extreme: ${r(notice.reference)}",
            "변화: ${pp(notice.delta)}",
            "기준: $window 안에서 ±${v(settings.swingThreshold)}%p 이상 · " +
                "같은 방향은 ${OwnerOpsAlertContent.threshold(settings.swingCooldown)} 동안 다시 알리지 않습니다.",
            "알림 시각: ${t(notice.at)}",
        )
    }

    private fun thresholdLines(notice: MarketNotice.ThresholdCrossed, t: (Instant) -> String): List<String> {
        val side = if (notice.line == ThresholdLine.UPPER) "상단" else "하단"
        return listOf(
            "현재 김프: ${r(notice.current)} (${t(notice.currentObservedAt)})",
            "$side 기준선: ${r(notice.threshold)}",
            "기준선 안쪽으로 ${v(notice.hysteresis)}%p 이상 돌아온 뒤 다시 넘으면 다시 알립니다.",
            "알림 시각: ${t(notice.at)}",
        )
    }

    private fun extremeLines(
        notice: MarketNotice.NewExtreme,
        settings: MarketAlertSettings,
        t: (Instant) -> String,
    ): List<String> {
        val side = if (notice.direction == MarketDirection.UP) "고점" else "저점"
        val cooldown = if (notice.window == ExtremeWindow.ONE_HOUR) settings.extremeHourCooldown else settings.extremeDayCooldown
        return listOf(
            "현재 김프: ${r(notice.current)} (${t(notice.currentObservedAt)})",
            "직전 ${windowLabel(notice.window)} $side: ${r(notice.previous)} (차이 ${pp(notice.current - notice.previous)})",
            "기준: 최소 ${v(settings.extremeMinDelta)}%p · " +
                "같은 창·방향은 ${OwnerOpsAlertContent.threshold(cooldown)} 동안 다시 알리지 않습니다.",
            "알림 시각: ${t(notice.at)}",
        )
    }

    private fun summaryLines(
        notice: MarketNotice.DailySummary,
        settings: MarketAlertSettings,
        zone: ZoneId,
        t: (Instant) -> String,
    ): List<String> {
        val fx = "${notice.fxBase.code}/${notice.fxQuote.code}"
        val day = notice.day
        val premiumLines = if (day == null) {
            listOf("김프: 전일 집계 없음")
        } else {
            listOf(
                "김프 시가 ${r(day.open)} · 고가 ${r(day.high)} · 저가 ${r(day.low)} · 종가 ${r(day.close)} · 평균 ${r(day.avg)}",
                "환율($fx) 전일 마감: ${day.fxRate?.let(::rate) ?: "없음"}",
            )
        }
        val current = notice.fx
        val fxLine = if (current == null) {
            "환율($fx) 현재: 없음"
        } else {
            "환율($fx) 현재: ${rate(current.rate)} (${t(current.observedAt)})"
        }
        return listOf("대상일: ${notice.date} 00:00 ~ 24:00 (${zone.id})") + premiumLines + fxLine +
            pnlLine(notice.pnl, settings, t) + "요약 시각: ${t(notice.at)}"
    }

    private fun pnlLine(pnl: SummaryPnl, settings: MarketAlertSettings, t: (Instant) -> String): String = when (pnl) {
        is SummaryPnl.Value ->
            "실계정 순수익률: ${r(pnl.netPercent)} (${AccountPnlFormat.krw(pnl.netKrw)}) · 계산 ${t(pnl.calculatedAt)}"

        is SummaryPnl.NoPosition -> "실계정 순수익률: 포지션 없음 · 계산 ${t(pnl.calculatedAt)}"

        SummaryPnl.Unavailable ->
            "실계정 순수익률: 없음 (PnL 모니터 꺼짐 또는 최근 ${OwnerOpsAlertContent.threshold(settings.summaryPnlMaxAge)} 안에 계산 없음)"
    }

    private fun r(value: BigDecimal): String = AccountPnlFormat.percent(value)

    private fun pp(value: BigDecimal): String = AccountPnlFormat.percentPoint(value)

    private fun v(value: BigDecimal): String = AccountPnlFormat.plain(value)

    private fun rate(value: BigDecimal): String = AccountPnlFormat.format("#,##0.00", value)
}
