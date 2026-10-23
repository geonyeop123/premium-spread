package io.premiumspread.domain.accountpnl

import io.premiumspread.domain.accountpnl.AccountPnlFormat.date
import io.premiumspread.domain.accountpnl.AccountPnlFormat.format
import io.premiumspread.domain.accountpnl.AccountPnlFormat.krw
import io.premiumspread.domain.accountpnl.AccountPnlFormat.percent
import io.premiumspread.domain.accountpnl.AccountPnlFormat.percentPoint
import io.premiumspread.domain.accountpnl.AccountPnlFormat.plain
import io.premiumspread.domain.accountpnl.AccountPnlFormat.plainKrw
import io.premiumspread.domain.accountpnl.AccountPnlFormat.shortTime
import io.premiumspread.domain.accountpnl.AccountPnlFormat.signedUsdt
import io.premiumspread.domain.accountpnl.AccountPnlFormat.time
import io.premiumspread.domain.owneralert.DiscordMessage
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/** owner 계정 알림 3종의 메일·Discord 문구 (design §7). 숫자·시각 서식은 [AccountPnlFormat] 을 쓴다. */
object AccountAlertContent {
    const val COLOR_POSITION_CHANGED: Int = 0xF1C40F
    const val COLOR_HEDGE_MISMATCH: Int = 0xE67E22
    const val COLOR_SWING_UP: Int = 0x1ABC9C
    const val COLOR_SWING_DOWN: Int = 0xE91E63
    const val COLOR_REMINDER: Int = 0x7F8C8D

    private const val QUANTITY_UNIT = "BTC"
    private const val NONE = "없음"
    private const val RUNBOOK_LINE = "조치 안내: ${AccountPnlFormat.RUNBOOK}"
    private const val WARNING_HEADLINE = "⚠ 바이낸스 env 미갱신 — 헤지 불일치"

    /** design §3.6 — `account-pnl-position-{ms}` / `account-pnl-swing-{up|down}-{ms}` / `account-pnl-reminder-{ms}`. */
    fun eventKey(alert: AccountAlert): String = when (alert) {
        is AccountAlert.PositionChanged -> "account-pnl-position-${alert.detectedAt.toEpochMilli()}"
        is AccountAlert.NetPercentSwing -> {
            val direction = if (alert.direction == SwingDirection.UP) "up" else "down"
            "account-pnl-swing-$direction-${alert.bucketStart.toEpochMilli()}"
        }
        is AccountAlert.UnrecoveredReminder -> "account-pnl-reminder-${alert.dayStart.toEpochMilli()}"
    }

    fun mail(alert: AccountAlert, zone: ZoneId): AccountPnlMail {
        val (subject, lines) = when (alert) {
            is AccountAlert.PositionChanged -> positionSubject(alert) to positionMailLines(alert, zone)
            is AccountAlert.NetPercentSwing -> swingSubject(alert) to swingMailLines(alert, zone)
            is AccountAlert.UnrecoveredReminder -> reminderSubject(alert, zone) to reminderMailLines(alert, zone)
        }
        return AccountPnlMail(eventKey(alert), subject, lines.joinToString("\n"))
    }

    fun discord(alert: AccountAlert, zone: ZoneId): DiscordMessage = when (alert) {
        is AccountAlert.PositionChanged -> DiscordMessage.of(
            if (alert.hedgeMismatchWarning) {
                "⚠️ 포지션 변경 — ${changedLegs(alert)} · 헤지 불일치"
            } else {
                "🔄 포지션 변경 — ${changedLegs(alert)}"
            },
            positionDiscordLines(alert, zone).joinToString("\n"),
            if (alert.hedgeMismatchWarning) COLOR_HEDGE_MISMATCH else COLOR_POSITION_CHANGED,
        )
        is AccountAlert.NetPercentSwing -> {
            val up = alert.direction == SwingDirection.UP
            DiscordMessage.of(
                (if (up) "📈 순수익률 급등 " else "📉 순수익률 급락 ") + percentPoint(alert.delta),
                swingDiscordLines(alert, zone).joinToString("\n"),
                if (up) COLOR_SWING_UP else COLOR_SWING_DOWN,
            )
        }
        is AccountAlert.UnrecoveredReminder -> DiscordMessage.of(
            "🗒️ 미반영 리마인더 · ${alert.items.size}건",
            reminderDiscordLines(alert, zone).joinToString("\n"),
            COLOR_REMINDER,
        )
    }

    // 포지션 변경

    private fun changedLegs(alert: AccountAlert.PositionChanged): String = when {
        alert.koreaChanged && alert.foreignChanged -> "빗썸·바이낸스"
        alert.koreaChanged -> "빗썸"
        else -> "바이낸스"
    }

    private fun positionSubject(alert: AccountAlert.PositionChanged): String =
        "[김프 알림] 포지션 변경 — ${changedLegs(alert)}" + if (alert.hedgeMismatchWarning) " · 헤지 불일치 확인" else ""

    private fun positionMailLines(alert: AccountAlert.PositionChanged, zone: ZoneId): List<String> = buildList {
        add("실계정 포지션 변경을 감지했습니다.")
        add(
            if (alert.koreaChanged) {
                "빗썸 현물: ${koreaLeg(alert.before.korea)} → ${koreaLeg(alert.after.korea)}"
            } else {
                "빗썸 현물(변경 없음): ${koreaLeg(alert.after.korea)}"
            },
        )
        add(
            if (alert.foreignChanged) {
                "바이낸스 선물: ${foreignLeg(alert.before.foreign)} → ${foreignLeg(alert.after.foreign)}"
            } else {
                "바이낸스 선물(변경 없음): ${foreignLeg(alert.after.foreign)}"
            },
        )
        add("바이낸스 포지션 출처: ${source(alert.source)}")
        add("감지 시각: ${time(alert.detectedAt, zone)}")
        if (alert.hedgeMismatchWarning) {
            add("")
            add(WARNING_HEADLINE)
            add(
                "빗썸 포지션만 바뀌었고 바이낸스 포지션은 수동 입력(env) 값 그대로입니다. 바이낸스 포지션도 바꿨다면 " +
                    "BINANCE_MANUAL_SHORT_QUANTITY·BINANCE_MANUAL_ENTRY_PRICE 를 고치고 batch 를 재시작하세요. " +
                    "이 경고는 바이낸스 값이 바뀔 때까지 다시 보내지 않습니다.",
            )
        }
        add("")
        add("순수익률 급변 기준선은 다음 정시 첫 성공 회차에 다시 잡습니다.")
        add(RUNBOOK_LINE)
    }

    private fun positionDiscordLines(alert: AccountAlert.PositionChanged, zone: ZoneId): List<String> = buildList {
        add(
            if (alert.koreaChanged) {
                "빗썸 ${koreaLeg(alert.before.korea)} → ${koreaLeg(alert.after.korea)}"
            } else {
                "빗썸 ${koreaLeg(alert.after.korea)} (변경 없음)"
            },
        )
        add(
            if (alert.foreignChanged) {
                "바이낸스 ${foreignLeg(alert.before.foreign)} → ${foreignLeg(alert.after.foreign)}"
            } else {
                "바이낸스 ${foreignLeg(alert.after.foreign)} (변경 없음)"
            },
        )
        if (alert.hedgeMismatchWarning) {
            add("$WARNING_HEADLINE. BINANCE_MANUAL_SHORT_QUANTITY·BINANCE_MANUAL_ENTRY_PRICE 갱신 후 재시작")
        }
        add("바이낸스 포지션: ${source(alert.source)} · ${shortTime(alert.detectedAt, zone)}")
    }

    // 순수익률 급변

    private fun swingSubject(alert: AccountAlert.NetPercentSwing): String {
        val direction = if (alert.direction == SwingDirection.UP) "▲ 상승" else "▼ 하락"
        return "[김프 알림] 순수익률 급변 $direction ${percentPoint(alert.delta)} · 현재 ${percent(alert.pnl.netPercent)}"
    }

    private fun swingMailLines(alert: AccountAlert.NetPercentSwing, zone: ZoneId): List<String> {
        val verb = if (alert.direction == SwingDirection.UP) "올랐" else "내렸"
        return listOf(
            "순수익률이 이번 시간 기준선에서 ${format("0.00", alert.threshold)}%p 이상 ${verb}습니다.",
            "기준선(${hour(alert.bucketStart, zone)}:00 시간대 첫 성공 회차): ${percent(alert.baselinePercent)}",
            "현재: ${percent(alert.pnl.netPercent)} (변화 ${percentPoint(alert.delta)})",
            "순손익: ${krw(alert.pnl.netKrw)} · 체결가 기준 ${percent(alert.pnl.netPercentAtLast)}",
            "계산 시각: ${time(alert.pnl.calculatedAt, zone)}",
            "",
            "같은 시간대 안에서 같은 방향은 한 번만 알립니다. 기준선은 매시 첫 성공 회차에 다시 잡습니다.",
        )
    }

    private fun swingDiscordLines(alert: AccountAlert.NetPercentSwing, zone: ZoneId): List<String> = listOf(
        "${hour(alert.bucketStart, zone)}:00 기준 ${percent(alert.baselinePercent)} → " +
            "현재 ${percent(alert.pnl.netPercent)}",
        "순손익 ${krw(alert.pnl.netKrw)} · 체결가 기준 ${percent(alert.pnl.netPercentAtLast)}",
        "임계값 ±${format("0.00", alert.threshold)}%p · ${shortTime(alert.pnl.calculatedAt, zone)}",
    )

    // 미반영 리마인더

    private fun reminderSubject(alert: AccountAlert.UnrecoveredReminder, zone: ZoneId): String =
        "[김프 알림] 미반영 항목 리마인더 ${date(alert.dayStart, zone)} · ${alert.items.size}건"

    private fun reminderItemText(
        alert: AccountAlert.UnrecoveredReminder,
        item: ReminderItem,
        zone: ZoneId,
    ): String {
        val foreignReason = if (alert.source == ForeignPerpPositionSource.MANUAL_ENV) "수동 미입력" else "이력 없음"
        return when (item) {
            ReminderItem.KOREA_ENTRY_FEE -> "진입 수수료(빗썸) 미반영 — 이력 없음"
            ReminderItem.FOREIGN_ENTRY_FEE -> "진입 수수료(바이낸스) 미반영 — $foreignReason"
            ReminderItem.FUNDING -> "펀딩비 미반영 — $foreignReason"
            ReminderItem.MANUAL_FUNDING_STALE -> {
                val funding = requireNotNull(alert.manualFunding) { "manual funding staleness is required." }
                "수동 펀딩비 ${funding.days}일째 미갱신 — 현재 값 ${signedUsdt(funding.fundingUsdt)}, " +
                    "마지막 변경 ${time(funding.changedAt, zone)}"
            }
        }
    }

    private fun reminderMailLines(alert: AccountAlert.UnrecoveredReminder, zone: ZoneId): List<String> = buildList {
        add("아래 항목이 순손익 계산에 반영되지 않았거나 오래 갱신되지 않았습니다.")
        alert.items.forEach { add("- ${reminderItemText(alert, it, zone)}") }
        add("")
        val hourText = "%02d".format(Locale.ROOT, alert.reminderHour)
        add("하루 한 번, $hourText:00 이후 항목이 처음 확인된 회차에 보냅니다. $RUNBOOK_LINE")
    }

    private fun reminderDiscordLines(alert: AccountAlert.UnrecoveredReminder, zone: ZoneId): List<String> = buildList {
        alert.items.forEach { add("• ${reminderItemText(alert, it, zone)}") }
        add("바이낸스 포지션: ${source(alert.source)} · ${shortTime(alert.detectedAt, zone)}")
    }

    // 공용 표기

    private fun koreaLeg(leg: KoreaLeg): String =
        if (leg.isEmpty) NONE else "${plain(leg.quantity)} $QUANTITY_UNIT · 평단 ${plainKrw(leg.averageBuyPrice)}원"

    private fun foreignLeg(leg: ForeignLeg): String = when {
        leg.isFlat -> NONE
        else -> {
            val side = if (leg.signedQuantity.signum() < 0) "숏" else "롱"
            "$side ${plain(leg.signedQuantity.abs())} $QUANTITY_UNIT · 진입가 ${plain(leg.entryPrice)} USDT"
        }
    }

    private fun source(source: ForeignPerpPositionSource): String = when (source) {
        ForeignPerpPositionSource.MANUAL_ENV -> "수동 입력(env)"
        ForeignPerpPositionSource.EXCHANGE_API -> "거래소 API"
    }

    private fun hour(instant: Instant, zone: ZoneId): String =
        "%02d".format(Locale.ROOT, instant.atZone(zone).hour)
}
