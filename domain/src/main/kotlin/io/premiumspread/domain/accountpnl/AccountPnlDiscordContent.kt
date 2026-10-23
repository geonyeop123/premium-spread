package io.premiumspread.domain.accountpnl

import io.premiumspread.domain.accountpnl.AccountPnlFormat.cost
import io.premiumspread.domain.accountpnl.AccountPnlFormat.format
import io.premiumspread.domain.accountpnl.AccountPnlFormat.krw
import io.premiumspread.domain.accountpnl.AccountPnlFormat.krwOrZero
import io.premiumspread.domain.accountpnl.AccountPnlFormat.percent
import io.premiumspread.domain.accountpnl.AccountPnlFormat.plain
import io.premiumspread.domain.accountpnl.AccountPnlFormat.shortTime
import io.premiumspread.domain.owneralert.DiscordMessage
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** 실계정 순손익 알림의 Discord embed 제목·설명·색 (design §6.2). 메일과 같은 숫자 서식을 쓴다. */
object AccountPnlDiscordContent {
    const val COLOR_PROFIT: Int = 0x2ECC71
    const val COLOR_HOURLY: Int = 0x3498DB
    const val COLOR_OUTAGE: Int = 0xE74C3C
    const val COLOR_RECOVERED: Int = 0x95A5A6

    /** 모니터는 BTC 단일 계정이다. */
    private const val QUANTITY_UNIT = "BTC"
    private val HOUR_FORMAT = DateTimeFormatter.ofPattern("HH")

    fun profit(pnl: AccountNetPnl, zone: ZoneId): DiscordMessage =
        DiscordMessage.of("💰 수익 알림 · 순수익률 ${percent(pnl.netPercent)}", description(pnl, zone), COLOR_PROFIT)

    fun hourly(pnl: AccountNetPnl, zone: ZoneId): DiscordMessage {
        val bucket = pnl.calculatedAt.atZone(zone).truncatedTo(ChronoUnit.HOURS)
        return DiscordMessage.of("📊 정시 보고 ${HOUR_FORMAT.format(bucket)}:00", description(pnl, zone), COLOR_HOURLY)
    }

    fun outage(notice: OutageNotice.Outage, now: Instant, zone: ZoneId): DiscordMessage {
        val label = AccountPnlFormat.label(notice.category)
        val lines = buildList {
            add("실계정 순손익 모니터링이 5분 넘게 중단되었습니다.")
            add("마지막 성공: ${notice.lastSuccessAt?.let { shortTime(it, zone) } ?: "없음"}")
            add("알림 시각: ${shortTime(now, zone)}")
            if (notice.category == AccountReadFailureCategory.BITHUMB_AUTH) {
                add("빗썸 Open API key 의 허용 IP 가 현재 공인 IP 와 같은지 확인하세요.")
            }
            add("조치 안내: ${AccountPnlFormat.RUNBOOK}")
        }
        return DiscordMessage.of("🚨 모니터링 중단 — $label", lines.joinToString("\n"), COLOR_OUTAGE)
    }

    fun recovered(now: Instant, zone: ZoneId): DiscordMessage =
        DiscordMessage.of(
            "✅ 모니터링 복구",
            "실계정 순손익 모니터링이 복구되었습니다.\n복구 시각: ${shortTime(now, zone)}",
            COLOR_RECOVERED,
        )

    private fun description(pnl: AccountNetPnl, zone: ZoneId): String {
        val korea = pnl.position.korea
        val foreign = pnl.position.foreign
        val manual = foreign.positionSource == ForeignPerpPositionSource.MANUAL_ENV
        val reason = if (manual) "수동 미입력" else "이력 없음"
        val unrecovered = buildList {
            if (pnl.koreaEntryFeeUnrecovered) add("진입 수수료(빗썸, 이력 없음)")
            if (pnl.foreignEntryFeeUnrecovered) add("진입 수수료(바이낸스, $reason)")
            if (pnl.fundingUnrecovered) add("펀딩비($reason)")
        }
        return buildList {
            add("순수익률 ${percent(pnl.netPercent)} · 순손익 ${krw(pnl.netKrw)}")
            add("체결가 기준 ${percent(pnl.netPercentAtLast)} (${krw(pnl.netKrwAtLast)})")
            add("빗썸 청산가 ${format("#,##0", pnl.koreaExitPrice)}원 × ${plain(korea.quantity)} $QUANTITY_UNIT (호가 VWAP)")
            add(
                "바이낸스 청산가 ${format("#,##0.####", pnl.foreignExitPrice)} USDT × " +
                    "${plain(foreign.signedQuantity.abs())} $QUANTITY_UNIT · 환율 ${format("#,##0.00", pnl.fxRate)}",
            )
            add("gross ${krw(pnl.grossKrw)} · 예상 청산 수수료 ${cost(pnl.exitFeeEstimateKrw)}")
            add("진입 수수료 ${cost(pnl.entryFeeKrw)} · 펀딩 ${krwOrZero(pnl.fundingKrw)}")
            if (unrecovered.isNotEmpty()) add("⚠ 미반영: ${unrecovered.joinToString(", ")}")
            if (korea.averageBuyPriceModified) add("빗썸 평단 사용자 수정됨")
            val source = if (manual) "수동 입력(env)" else "거래소 API"
            add("바이낸스 포지션: $source · ${shortTime(pnl.calculatedAt, zone)}")
        }.joinToString("\n")
    }
}
