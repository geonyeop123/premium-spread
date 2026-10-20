package io.premiumspread.domain.accountpnl

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class AccountPnlMail(val eventKey: String, val subject: String, val body: String)

/** 실계정 순손익 알림 메일의 제목·본문 (design §4.3). 숫자·시각 표기는 locale 과 무관하다. */
object AccountPnlMailContent {
    private const val RUNBOOK = "docs/runbooks/account-pnl-alert.md"
    private val TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    fun profit(pnl: AccountNetPnl, zone: ZoneId): AccountPnlMail {
        val subject = "[김프 알림] 순수익률 ${percent(pnl.netPercent)} · ${krw(pnl.netKrw)}"
        val korea = pnl.position.korea
        val foreign = pnl.position.foreign
        val lines = buildList {
            add("순수익률: ${percent(pnl.netPercent)}")
            add("순수익금: ${krw(pnl.netKrw)}")
            add("체결가 기준 순수익률(대조용): ${percent(pnl.netPercentAtLast)} / 순수익금 ${krw(pnl.netKrwAtLast)}")
            add("")
            add("gross: ${krw(pnl.grossKrw)}")
            add("진입 수수료: ${cost(pnl.entryFeeKrw)}")
            if (pnl.koreaEntryFeeUnrecovered) {
                add("  - 빗썸: 진입 수수료(빗썸) 미반영(이력 없음)")
            } else {
                add("  - 빗썸: ${cost(korea.entryFeeKrw ?: BigDecimal.ZERO)}")
            }
            val commission = foreign.entryCommissionUsdt
            if (commission == null) {
                add("  - 바이낸스: 진입 수수료(바이낸스) 미반영(이력 없음)")
            } else {
                add("  - 바이낸스: ${costUsdt(commission)} (${cost(commission.multiply(pnl.fxRate))})")
            }
            val funding = foreign.fundingUsdt
            if (funding == null) {
                add("누적 펀딩비: ${krwOrZero(pnl.fundingKrw)}  펀딩비 미반영(이력 없음)")
            } else {
                add("누적 펀딩비: ${krwOrZero(pnl.fundingKrw)} (${signedUsdt(funding)})")
            }
            add("예상 청산 수수료: ${cost(pnl.exitFeeEstimateKrw)}")
            if (korea.averageBuyPriceModified) add("빗썸 평단 사용자 수정됨")
            add("")
            add("[빗썸 현물]")
            add("수량: ${plain(korea.quantity)}")
            add("평단: ${plainKrw(korea.averageBuyPrice)}원")
            add("청산가(호가 VWAP): ${format("#,##0.00", pnl.koreaExitPrice)}원")
            add("최근 체결가: ${plainKrw(korea.lastPrice)}원")
            add("")
            add("[바이낸스 선물 숏]")
            add("수량: ${plain(foreign.signedQuantity.abs())}")
            add("진입가: ${plain(foreign.entryPrice)} USDT")
            add("청산가(호가 VWAP): ${pnl.foreignExitPrice.setScale(FOREIGN_PRICE_SCALE, RoundingMode.HALF_UP).toPlainString()} USDT")
            add("최근 체결가: ${plain(foreign.lastPrice)} USDT")
            add("")
            add("환율: ${plain(pnl.fxRate)} KRW/USDT")
            add("빗썸 호가 관측: ${time(korea.bidObservedAt, zone)}")
            add("바이낸스 호가 관측: ${time(foreign.askObservedAt, zone)}")
            add("환율 관측: ${time(pnl.fxObservedAt, zone)}")
            add("계산 시각: ${time(pnl.calculatedAt, zone)}")
            add("")
            add("※ 금액은 표시 시 원 단위 반올림(계산은 전체 정밀도)")
        }
        return AccountPnlMail("profit|${pnl.calculatedAt}", subject, lines.joinToString("\n"))
    }

    fun outage(notice: OutageNotice.Outage, now: Instant, zone: ZoneId): AccountPnlMail {
        val label = label(notice.category)
        val lines = buildList {
            add("실계정 순손익 모니터링이 5분 넘게 중단되었습니다.")
            add("원인 범주: $label")
            add("마지막 성공: ${notice.lastSuccessAt?.let { time(it, zone) } ?: "없음"}")
            add("알림 시각: ${time(now, zone)}")
            if (notice.category == AccountReadFailureCategory.BITHUMB_AUTH) {
                add("빗썸 Open API key 의 허용 IP 가 현재 공인 IP 와 같은지 확인하세요.")
            }
            add("조치 안내: $RUNBOOK")
        }
        return AccountPnlMail("outage|$now", "[김프 알림] 모니터링 중단 — $label", lines.joinToString("\n"))
    }

    fun recovered(now: Instant, zone: ZoneId): AccountPnlMail =
        AccountPnlMail(
            "recovered|$now",
            "[김프 알림] 모니터링 복구",
            "실계정 순손익 모니터링이 복구되었습니다.\n복구 시각: ${time(now, zone)}",
        )

    private const val FOREIGN_PRICE_SCALE = 4

    private fun label(category: AccountReadFailureCategory): String = when (category) {
        AccountReadFailureCategory.BITHUMB_AUTH -> "빗썸 인증 실패(허용 IP 확인)"
        AccountReadFailureCategory.BINANCE_AUTH -> "바이낸스 인증 실패"
        AccountReadFailureCategory.NETWORK -> "네트워크·요청 한도 오류"
        AccountReadFailureCategory.STALE_MARKET_DATA -> "시세 지연"
        AccountReadFailureCategory.STALE_FX -> "환율 수집 지연"
        AccountReadFailureCategory.INSUFFICIENT_DEPTH -> "호가 깊이 부족"
        AccountReadFailureCategory.UNEXPECTED -> "기타 오류(로그 확인)"
    }

    private fun time(instant: Instant, zone: ZoneId): String =
        "${TIME_FORMAT.format(instant.atZone(zone))} ($instant)"

    private fun plain(value: BigDecimal): String = value.stripTrailingZeros().toPlainString()

    private fun format(pattern: String, value: BigDecimal): String =
        DecimalFormat(pattern, DecimalFormatSymbols(Locale.ROOT)).apply { roundingMode = RoundingMode.HALF_UP }
            .format(value)

    private fun sign(value: BigDecimal): String = if (value.signum() < 0) "-" else "+"

    private fun percent(value: BigDecimal): String = sign(value) + format("0.00", value.abs()) + "%"

    /** 부호 포함 원 단위 금액. 반올림 후 0 이면 `+0원`. */
    private fun krw(value: BigDecimal): String {
        val rounded = value.setScale(0, RoundingMode.HALF_UP)
        return sign(rounded) + format("#,##0", rounded.abs()) + "원"
    }

    /** 부호 없는 원 단위 천 단위 구분 표기. */
    private fun plainKrw(value: BigDecimal): String = format("#,##0", value.abs().setScale(0, RoundingMode.HALF_UP))

    private fun isZero(value: BigDecimal): Boolean = value.setScale(0, RoundingMode.HALF_UP).signum() == 0

    /** 차감 항목(수수료). 반올림 후 0 이면 부호 없이 `0원`. */
    private fun cost(value: BigDecimal): String =
        if (isZero(value)) "0원" else "-${plainKrw(value)}원"

    /** 부호 있는 금액이되 반올림 후 0 이면 `0원`. */
    private fun krwOrZero(value: BigDecimal): String = if (isZero(value)) "0원" else krw(value)

    private fun costUsdt(value: BigDecimal): String =
        if (value.signum() == 0) "0 USDT" else "-${plain(value.abs())} USDT"

    private fun signedUsdt(value: BigDecimal): String =
        if (value.signum() == 0) "0 USDT" else "${sign(value)}${plain(value.abs())} USDT"
}
