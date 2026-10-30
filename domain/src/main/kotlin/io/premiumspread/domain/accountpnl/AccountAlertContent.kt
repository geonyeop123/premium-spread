package io.premiumspread.domain.accountpnl

import io.premiumspread.domain.alertmessage.AlertFormat
import io.premiumspread.domain.alertmessage.EmbedCell
import io.premiumspread.domain.alertmessage.EmbedDocument
import io.premiumspread.domain.alertmessage.MailBlock
import io.premiumspread.domain.alertmessage.MailDocument
import io.premiumspread.domain.alertmessage.MailPair
import io.premiumspread.domain.alertmessage.OwnerAlertMessage
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/** 계정 알림 3종(포지션 변경·순수익률 급변·미반영 리마인더)의 메일·Discord 문구. 문구 정본 message-spec.md "계정 알림", 값 출처 design §6.3. */
object AccountAlertContent {
    const val COLOR_POSITION_CHANGED: Int = 0xF1C40F
    const val COLOR_HEDGE_MISMATCH: Int = 0xE67E22
    const val COLOR_SWING_UP: Int = 0x1ABC9C
    const val COLOR_SWING_DOWN: Int = 0xE91E63
    const val COLOR_REMINDER: Int = 0x7F8C8D

    private const val NONE = "포지션 없음"
    private const val KOREA_NAME = "빗썸 현물"
    private const val FOREIGN_NAME = "바이낸스 선물 숏"
    private const val UNCHANGED = " (변경 없음)"
    private const val BASELINE_FOOTER = "순수익률 급변 기준선은 다음 정시에 다시 잡습니다"
    private const val HEDGE_FOOTER = "바이낸스 값이 바뀔 때까지 이 경고는 다시 보내지 않습니다"
    private const val HEDGE_GUIDE_HEAD = "바이낸스는 수동 입력값 그대로입니다. 바이낸스 포지션도 바꿨다면"
    private const val HEDGE_GUIDE_TAIL = "BINANCE_MANUAL_SHORT_QUANTITY · BINANCE_MANUAL_ENTRY_PRICE를 고치고 batch를 재시작하세요."

    // 경고 이모지(variation selector 포함)
    private const val WARNING = "⚠️"

    /** 무변경 — `account-pnl-position-{ms}` / `account-pnl-swing-{up|down}-{bucketStart ms}` / `account-pnl-reminder-{dayStart ms}`. */
    fun eventKey(alert: AccountAlert): String = when (alert) {
        is AccountAlert.PositionChanged -> "account-pnl-position-${alert.detectedAt.toEpochMilli()}"

        is AccountAlert.NetPercentSwing -> {
            val direction = if (alert.direction == SwingDirection.UP) "up" else "down"
            "account-pnl-swing-$direction-${alert.bucketStart.toEpochMilli()}"
        }

        is AccountAlert.UnrecoveredReminder -> "account-pnl-reminder-${alert.dayStart.toEpochMilli()}"
    }

    /** basis: 포지션·리마인더 = detectedAt, 급변 = pnl.calculatedAt. author = [AccountPnlAlertContent.AUTHOR]. */
    fun message(alert: AccountAlert, zone: ZoneId): OwnerAlertMessage = when (alert) {
        is AccountAlert.PositionChanged -> positionChanged(alert, zone)
        is AccountAlert.NetPercentSwing -> swing(alert, zone)
        is AccountAlert.UnrecoveredReminder -> reminder(alert, zone)
    }

    // 포지션 변경

    private fun positionChanged(alert: AccountAlert.PositionChanged, zone: ZoneId): OwnerAlertMessage {
        val legs = when {
            alert.koreaChanged && alert.foreignChanged -> "빗썸·바이낸스"
            alert.koreaChanged -> "빗썸"
            else -> "바이낸스"
        }
        val headline = "포지션 변경 — $legs" + if (alert.hedgeMismatchWarning) " · 헤지 불일치" else ""
        val beforeKorea = koreaLeg(alert.before.korea)
        val afterKorea = koreaLeg(alert.after.korea)
        val beforeForeign = foreignLeg(alert.before.foreign)
        val afterForeign = foreignLeg(alert.after.foreign)

        val koreaCell = if (alert.koreaChanged) {
            EmbedCell(KOREA_NAME, "$beforeKorea\n→ $afterKorea")
        } else {
            val value = if (alert.after.korea.isEmpty) NONE else "${qty(alert.after.korea.quantity)}\n평단 ${krw(alert.after.korea)}"
            EmbedCell(KOREA_NAME + UNCHANGED, value)
        }
        val foreignCell = if (alert.foreignChanged) {
            EmbedCell(FOREIGN_NAME, "$beforeForeign\n→ $afterForeign")
        } else {
            EmbedCell(FOREIGN_NAME + UNCHANGED, afterForeign)
        }
        val rows = buildList {
            add(listOf(koreaCell, foreignCell))
            if (alert.hedgeMismatchWarning) add(listOf(EmbedCell("확인할 것", "$HEDGE_GUIDE_HEAD $HEDGE_GUIDE_TAIL")))
        }

        val koreaBlock = if (alert.koreaChanged) {
            changedSection(KOREA_NAME, beforeKorea, afterKorea)
        } else {
            MailBlock.Section(KOREA_NAME + UNCHANGED, listOf(MailBlock.Text(listOf(afterKorea))))
        }
        val foreignBlock = if (alert.foreignChanged) {
            changedSection(FOREIGN_NAME, beforeForeign, afterForeign)
        } else {
            MailBlock.Section(FOREIGN_NAME + UNCHANGED, listOf(MailBlock.Text(listOf(afterForeign))))
        }
        val mailBlocks = buildList {
            add(
                MailBlock.Text(
                    listOf("실계정 포지션이 바뀌었습니다." + if (alert.hedgeMismatchWarning) " 헤지가 맞지 않습니다." else ""),
                ),
            )
            add(koreaBlock)
            add(foreignBlock)
            if (alert.hedgeMismatchWarning) {
                add(MailBlock.Section("확인할 것", listOf(MailBlock.Text(listOf(HEDGE_GUIDE_HEAD, HEDGE_GUIDE_TAIL, "$HEDGE_FOOTER.")))))
            } else {
                add(MailBlock.Text(listOf("$BASELINE_FOOTER.")))
            }
        }
        return OwnerAlertMessage(
            eventKey = eventKey(alert),
            mail = MailDocument("[실계정] $headline", mailBlocks, alert.detectedAt, zone),
            discord = EmbedDocument(
                author = AccountPnlAlertContent.AUTHOR,
                title = (if (alert.hedgeMismatchWarning) "$WARNING " else "🔄 ") + headline,
                color = if (alert.hedgeMismatchWarning) COLOR_HEDGE_MISMATCH else COLOR_POSITION_CHANGED,
                rows = rows,
                footer = if (alert.hedgeMismatchWarning) HEDGE_FOOTER else BASELINE_FOOTER,
                timestamp = alert.detectedAt,
            ),
        )
    }

    private fun changedSection(title: String, before: String, after: String) = MailBlock.Section(
        title,
        listOf(MailBlock.Pairs(listOf(MailPair("변경 전", before), MailPair("변경 후", after)))),
    )

    // 순수익률 급변

    private fun swing(alert: AccountAlert.NetPercentSwing, zone: ZoneId): OwnerAlertMessage {
        val up = alert.direction == SwingDirection.UP
        val baselineLabel = "기준선 (${hour(alert.bucketStart, zone)}:00)"
        val baseline = AlertFormat.percent(alert.baselinePercent)
        val current = AlertFormat.percent(alert.pnl.netPercent)
        val delta = AlertFormat.percentPoint(alert.delta)
        val net = AlertFormat.krw(alert.pnl.netKrw)
        val threshold = AlertFormat.format("0.00", alert.threshold)
        val headline = "순수익률 ${if (up) "급등" else "급락"} $delta → $current"
        val at = alert.pnl.calculatedAt
        return OwnerAlertMessage(
            eventKey = eventKey(alert),
            mail = MailDocument(
                subject = "[실계정] $headline",
                blocks = listOf(
                    MailBlock.Text(listOf("순수익률이 이번 시간 기준선보다 $threshold%p 이상 ${if (up) "올랐" else "내렸"}습니다.")),
                    MailBlock.Pairs(
                        listOf(
                            MailPair(baselineLabel, baseline),
                            MailPair("현재", current),
                            MailPair("변화", delta),
                            MailPair("순손익", net),
                        ),
                    ),
                    MailBlock.Text(listOf("같은 시간대에 같은 방향은 1번만 알립니다.")),
                ),
                basis = at,
                zone = zone,
            ),
            discord = EmbedDocument(
                author = AccountPnlAlertContent.AUTHOR,
                title = (if (up) "📈 " else "📉 ") + headline,
                color = if (up) COLOR_SWING_UP else COLOR_SWING_DOWN,
                rows = listOf(
                    listOf(EmbedCell(baselineLabel, baseline), EmbedCell("현재", current), EmbedCell("변화", delta)),
                    listOf(EmbedCell("순손익", net)),
                ),
                footer = "기준 ±$threshold%p · 같은 시간대 같은 방향은 1번만 알림",
                timestamp = at,
            ),
        )
    }

    // 미반영 리마인더

    private fun reminder(alert: AccountAlert.UnrecoveredReminder, zone: ZoneId): OwnerAlertMessage {
        val foreignReason = if (alert.source == ForeignPerpPositionSource.MANUAL_ENV) "수동 미입력" else "이력 없음"
        val missing = alert.items.filter { it != ReminderItem.MANUAL_FUNDING_STALE }.map {
            when (it) {
                ReminderItem.KOREA_ENTRY_FEE -> "빗썸 진입 수수료 — 이력 없음"
                ReminderItem.FOREIGN_ENTRY_FEE -> "바이낸스 진입 수수료 — $foreignReason"
                else -> "펀딩비 — $foreignReason"
            }
        }
        val staleFunding = if (ReminderItem.MANUAL_FUNDING_STALE in alert.items) {
            requireNotNull(alert.manualFunding) { "manual funding staleness is required." }
        } else {
            null
        }
        val stale = staleFunding?.let {
            { changed: String -> "수동 펀딩비 ${it.days}일째 그대로 (${AlertFormat.signedUsdt(it.fundingUsdt)}, $changed 변경)" }
        }
        val hourText = "%02d".format(Locale.ROOT, alert.reminderHour)

        val mailBlocks = buildList {
            add(MailBlock.Text(listOf("순손익 계산에 빠졌거나 오래된 항목이 있습니다.")))
            if (missing.isNotEmpty()) add(MailBlock.Section("순손익에 빠진 항목", listOf(MailBlock.Bullets(missing))))
            if (stale != null) {
                val changed = AlertFormat.stamp(staleFunding.changedAt, zone)
                add(MailBlock.Section("오래된 항목", listOf(MailBlock.Bullets(listOf(stale(changed))))))
            }
            add(MailBlock.Text(listOf("하루 1번, $hourText:00 이후에 보냅니다.")))
        }
        val rows = buildList {
            if (missing.isNotEmpty()) add(listOf(EmbedCell("순손익에 빠진 항목", missing.joinToString("\n") { "• $it" })))
            if (stale != null) {
                val changed = AlertFormat.clock(staleFunding.changedAt, alert.detectedAt, zone)
                add(listOf(EmbedCell("오래된 항목", "• ${stale(changed)}")))
            }
        }
        val headline = "미반영 항목 ${alert.items.size}건"
        return OwnerAlertMessage(
            eventKey = eventKey(alert),
            mail = MailDocument("[실계정] $headline", mailBlocks, alert.detectedAt, zone),
            discord = EmbedDocument(
                author = AccountPnlAlertContent.AUTHOR,
                title = "🗒️ $headline",
                color = COLOR_REMINDER,
                rows = rows,
                footer = "하루 1번, $hourText:00 이후 발송",
                timestamp = alert.detectedAt,
            ),
        )
    }

    // 공용 표기

    private fun qty(value: java.math.BigDecimal): String = "${AlertFormat.plain(value)} BTC"

    private fun krw(leg: KoreaLeg): String = AlertFormat.krwPrice(leg.averageBuyPrice)

    private fun koreaLeg(leg: KoreaLeg): String =
        if (leg.isEmpty) NONE else "${qty(leg.quantity)} · 평단 ${krw(leg)}"

    /** 롱이면(A7) 앞에 `롱 `. flat 이면 [NONE]. */
    private fun foreignLeg(leg: ForeignLeg): String = when {
        leg.isFlat -> NONE

        else -> {
            val prefix = if (leg.signedQuantity.signum() > 0) "롱 " else ""
            "$prefix${qty(leg.signedQuantity.abs())} · 평단 ${AlertFormat.usdtPrice(leg.entryPrice)}"
        }
    }

    private fun hour(instant: Instant, zone: ZoneId): String = "%02d".format(Locale.ROOT, instant.atZone(zone).hour)
}
