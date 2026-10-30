package io.premiumspread.domain.ownermarket

import io.premiumspread.domain.alertmessage.AlertFormat
import io.premiumspread.domain.alertmessage.EmbedCell
import io.premiumspread.domain.alertmessage.EmbedDocument
import io.premiumspread.domain.alertmessage.MailBlock
import io.premiumspread.domain.alertmessage.MailDocument
import io.premiumspread.domain.alertmessage.MailPair
import io.premiumspread.domain.alertmessage.OwnerAlertMessage
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/** 시장 알림 7종(급변·기준선·신고/신저·일일 요약)의 메일·Discord 문구. 문구 정본 message-spec.md "시장 알림", 값 출처 design §6.5. */
object OwnerMarketAlertContent {
    const val UP_COLOR = 0xE74C3C
    const val DOWN_COLOR = 0x3498DB
    const val SUMMARY_COLOR = 0x95A5A6

    /** design §8.1. 무변경. */
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

    /** author = `시장 · {symbol} {korea}/{foreign}` — notice.pair 에서만 만든다. basis = notice.at. */
    fun message(notice: MarketNotice, settings: MarketAlertSettings, zone: ZoneId): OwnerAlertMessage {
        val author = "시장 · ${notice.pair.symbol.code} ${pairText(notice)}"
        val parts = when (notice) {
            is MarketNotice.Swing -> swing(notice, settings, zone)
            is MarketNotice.ThresholdCrossed -> crossed(notice, zone)
            is MarketNotice.NewExtreme -> extreme(notice, settings, zone)
            is MarketNotice.DailySummary -> summary(notice, zone)
        }
        return OwnerAlertMessage(
            eventKey = eventKey(notice),
            mail = MailDocument(
                subject = "[시장] ${parts.core} (${notice.pair.symbol.code})",
                blocks = parts.blocks,
                basis = notice.at,
                zone = zone,
            ),
            discord = EmbedDocument(
                author = author,
                title = "${parts.emoji} ${parts.core}",
                color = parts.color,
                rows = parts.rows,
                footer = parts.footer,
                timestamp = notice.at,
            ),
        )
    }

    private class Parts(
        val emoji: String,
        val core: String,
        val color: Int,
        val blocks: List<MailBlock>,
        val rows: List<List<EmbedCell>>,
        val footer: String,
    )

    // 유니코드 escape: 📈 📉 🔺 🔻 ⬆️ ⬇️ 🗓️ (variation selector 포함)
    private const val CHART_UP = "📈"
    private const val CHART_DOWN = "📉"
    private const val TRIANGLE_UP = "🔺"
    private const val TRIANGLE_DOWN = "🔻"
    private const val ARROW_UP = "⬆️"
    private const val ARROW_DOWN = "⬇️"
    private const val CALENDAR = "🗓️"

    private fun directionColor(direction: MarketDirection): Int =
        if (direction == MarketDirection.UP) UP_COLOR else DOWN_COLOR

    private fun pairText(notice: MarketNotice): String = AlertFormat.exchangePair(notice.pair, "/")

    private fun swing(notice: MarketNotice.Swing, settings: MarketAlertSettings, zone: ZoneId): Parts {
        val up = notice.direction == MarketDirection.UP
        val window = AlertFormat.threshold(notice.window)
        val threshold = AlertFormat.plain(settings.swingThreshold)
        val cooldown = AlertFormat.threshold(settings.swingCooldown)
        val reference = "$window ${if (up) "저점" else "고점"}"
        val intro = "${notice.pair.symbol.code} 김프가 $window 사이 ${magnitude(notice.delta)} " +
            "${if (up) "올랐습니다" else "내렸습니다"}. (${pairText(notice)})"
        val delta = AlertFormat.percentPoint(notice.delta)
        return Parts(
            emoji = if (up) CHART_UP else CHART_DOWN,
            core = "김프 ${if (up) "급등" else "급락"} $delta → ${AlertFormat.percent(notice.current)}",
            color = directionColor(notice.direction),
            blocks = listOf(
                MailBlock.Text(listOf(intro)),
                MailBlock.Pairs(
                    listOf(
                        currentPair(notice.current, notice.currentObservedAt, notice.at, zone),
                        MailPair(reference, AlertFormat.percent(notice.reference)),
                        MailPair("변화", delta),
                    ),
                ),
                MailBlock.Text(listOf("기준: $window 안에서 ±$threshold%p 이상. 같은 방향은 $cooldown 동안 다시 알리지 않습니다.")),
            ),
            rows = listOf(
                listOf(
                    EmbedCell("현재 김프", AlertFormat.percent(notice.current)),
                    EmbedCell(reference, AlertFormat.percent(notice.reference)),
                    EmbedCell("변화", delta),
                ),
            ),
            footer = "기준 $window 안 ±$threshold%p · 같은 방향은 $cooldown 동안 다시 알리지 않음",
        )
    }

    private fun crossed(notice: MarketNotice.ThresholdCrossed, zone: ZoneId): Parts {
        val upper = notice.line == ThresholdLine.UPPER
        val side = if (upper) "상단" else "하단"
        val hysteresis = AlertFormat.plain(notice.hysteresis)
        val action = if (upper) "상단 기준선을 넘었습니다" else "하단 기준선 아래로 내려갔습니다"
        return Parts(
            emoji = if (upper) TRIANGLE_UP else TRIANGLE_DOWN,
            core = "김프 ${if (upper) "상단 기준선 돌파" else "하단 기준선 이탈"} ${AlertFormat.percent(notice.current)}",
            color = if (upper) UP_COLOR else DOWN_COLOR,
            blocks = listOf(
                MailBlock.Text(listOf("${notice.pair.symbol.code} 김프가 $action. (${pairText(notice)})")),
                MailBlock.Pairs(
                    listOf(
                        currentPair(notice.current, notice.currentObservedAt, notice.at, zone),
                        MailPair("$side 기준선", AlertFormat.percent(notice.threshold)),
                    ),
                ),
                MailBlock.Text(listOf("기준선 안쪽으로 $hysteresis%p 이상 돌아온 뒤 다시 넘으면 다시 알립니다.")),
            ),
            rows = listOf(
                listOf(
                    EmbedCell("현재 김프", AlertFormat.percent(notice.current)),
                    EmbedCell("$side 기준선", AlertFormat.percent(notice.threshold)),
                ),
            ),
            footer = "기준선 안쪽으로 $hysteresis%p 이상 돌아온 뒤 다시 넘으면 다시 알림",
        )
    }

    private fun extreme(notice: MarketNotice.NewExtreme, settings: MarketAlertSettings, zone: ZoneId): Parts {
        val up = notice.direction == MarketDirection.UP
        val window = if (notice.window == ExtremeWindow.ONE_HOUR) "1시간" else "24시간"
        val cooldown = AlertFormat.threshold(
            if (notice.window == ExtremeWindow.ONE_HOUR) settings.extremeHourCooldown else settings.extremeDayCooldown,
        )
        val minDelta = AlertFormat.plain(settings.extremeMinDelta)
        val previousLabel = "직전 $window ${if (up) "고점" else "저점"}"
        val diff = AlertFormat.percentPoint(notice.current - notice.previous)
        return Parts(
            emoji = if (up) ARROW_UP else ARROW_DOWN,
            core = "김프 $window ${if (up) "신고가" else "신저가"} ${AlertFormat.percent(notice.current)}",
            color = directionColor(notice.direction),
            blocks = listOf(
                MailBlock.Text(
                    listOf("${notice.pair.symbol.code} 김프가 $window ${if (up) "고점" else "저점"}을 새로 썼습니다. (${pairText(notice)})"),
                ),
                MailBlock.Pairs(
                    listOf(
                        currentPair(notice.current, notice.currentObservedAt, notice.at, zone),
                        MailPair(previousLabel, AlertFormat.percent(notice.previous)),
                        MailPair("차이", diff),
                    ),
                ),
                MailBlock.Text(
                    listOf("최소 $minDelta%p 이상일 때만 알리고, 같은 기간·방향은 $cooldown 동안 다시 알리지 않습니다."),
                ),
            ),
            rows = listOf(
                listOf(
                    EmbedCell("현재 김프", AlertFormat.percent(notice.current)),
                    EmbedCell(previousLabel, AlertFormat.percent(notice.previous)),
                    EmbedCell("차이", diff),
                ),
            ),
            footer = "최소 $minDelta%p · 같은 기간·방향은 $cooldown 동안 다시 알리지 않음",
        )
    }

    private fun summary(notice: MarketNotice.DailySummary, zone: ZoneId): Parts {
        val abbreviation = AlertFormat.zoneAbbreviation(notice.at, zone)
        val monthDay = "%02d/%02d".format(Locale.ROOT, notice.date.monthValue, notice.date.dayOfMonth)
        val footerDay = "%02d-%02d".format(Locale.ROOT, notice.date.monthValue, notice.date.dayOfMonth)
        val base = notice.fxBase.code
        val quote = notice.fxQuote.code
        val krwQuote = quote == "KRW"
        val day = notice.day
        val previousFx = day?.fxRate
        val currentFx = notice.fx?.rate
        val pnlValue = when (val pnl = notice.pnl) {
            is SummaryPnl.Value -> "${AlertFormat.percent(pnl.netPercent)} (${AlertFormat.krw(pnl.netKrw)})"
            is SummaryPnl.NoPosition -> "포지션 없음"
            SummaryPnl.Unavailable -> "없음"
        }
        val premiumLines = if (day == null) {
            listOf("전일 집계 없음")
        } else {
            listOf(
                "시가 ${AlertFormat.percent(day.open)} · 고가 ${AlertFormat.percent(day.high)} · 저가 ${AlertFormat.percent(day.low)}",
                "종가 ${AlertFormat.percent(day.close)} · 평균 ${AlertFormat.percent(day.avg)}",
            )
        }
        val premiumRows = if (day == null) {
            listOf(listOf(EmbedCell("김프", "전일 집계 없음")))
        } else {
            listOf(
                listOf(
                    EmbedCell("시가", AlertFormat.percent(day.open)),
                    EmbedCell("고가", AlertFormat.percent(day.high)),
                    EmbedCell("저가", AlertFormat.percent(day.low)),
                ),
                listOf(
                    EmbedCell("종가", AlertFormat.percent(day.close)),
                    EmbedCell("평균", AlertFormat.percent(day.avg)),
                ),
            )
        }
        return Parts(
            emoji = CALENDAR,
            core = "김프 일일 요약 $monthDay",
            color = SUMMARY_COLOR,
            blocks = listOf(
                MailBlock.Text(
                    listOf(
                        "${notice.date} ${notice.pair.symbol.code} 김프 요약입니다. " +
                            "(${pairText(notice)}, 00:00 ~ 24:00 $abbreviation)",
                    ),
                ),
                MailBlock.Section("김프", listOf(MailBlock.Text(premiumLines))),
                MailBlock.Section(
                    "환율 ($base/$quote)",
                    listOf(
                        MailBlock.Pairs(
                            listOf(
                                MailPair("전일 마감", fxMail(previousFx, krwQuote, quote)),
                                MailPair("현재", fxMail(currentFx, krwQuote, quote)),
                            ),
                        ),
                    ),
                ),
                MailBlock.Section("실계정", listOf(MailBlock.Pairs(listOf(MailPair("순수익률", pnlValue))))),
            ),
            rows = premiumRows + listOf(
                listOf(
                    EmbedCell("환율 전일 마감", fxEmbed(previousFx, krwQuote, base, quote)),
                    EmbedCell("환율 현재", fxEmbed(currentFx, krwQuote, base, quote)),
                ),
                listOf(EmbedCell("실계정 순수익률", pnlValue)),
            ),
            footer = "대상 $footerDay 00:00 ~ 24:00 $abbreviation",
        )
    }

    private fun fxMail(rate: BigDecimal?, krwQuote: Boolean, quote: String): String =
        rate?.let { AlertFormat.rate(it) + if (krwQuote) "원" else " $quote" } ?: "없음"

    private fun fxEmbed(rate: BigDecimal?, krwQuote: Boolean, base: String, quote: String): String =
        rate?.let { AlertFormat.rate(it) + if (krwQuote) "원/$base" else " $quote/$base" } ?: "없음"

    private fun currentPair(current: BigDecimal, observedAt: Instant, at: Instant, zone: ZoneId): MailPair = MailPair(
        "현재 김프",
        "${AlertFormat.percent(current)} (${AlertFormat.clock(observedAt, at, zone)} 관측)",
    )

    /** 부호 없는 `0.60%p`. */
    private fun magnitude(value: BigDecimal): String = AlertFormat.format("0.00", value.abs()) + "%p"
}
