package io.premiumspread.domain.accountpnl

import io.premiumspread.domain.accountpnl.AccountPnlFormat.cost
import io.premiumspread.domain.accountpnl.AccountPnlFormat.costUsdt
import io.premiumspread.domain.accountpnl.AccountPnlFormat.format
import io.premiumspread.domain.accountpnl.AccountPnlFormat.krw
import io.premiumspread.domain.accountpnl.AccountPnlFormat.krwOrZero
import io.premiumspread.domain.accountpnl.AccountPnlFormat.label
import io.premiumspread.domain.accountpnl.AccountPnlFormat.percent
import io.premiumspread.domain.accountpnl.AccountPnlFormat.plain
import io.premiumspread.domain.accountpnl.AccountPnlFormat.plainKrw
import io.premiumspread.domain.accountpnl.AccountPnlFormat.signedUsdt
import io.premiumspread.domain.accountpnl.AccountPnlFormat.time
import io.premiumspread.domain.owneralert.DiscordRoute
import io.premiumspread.domain.owneralert.OwnerAlertChannel
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * owner 알림 공용 outbox 행 (이름은 호환 때문에 유지 — design §4.4).
 * EMAIL 행: subject/body = 메일 제목/본문. DISCORD 행: subject/body = embed 제목/설명, Discord 필드 필수.
 */
data class AccountPnlMail(
    val eventKey: String,
    val subject: String,
    val body: String,
    val channel: OwnerAlertChannel = OwnerAlertChannel.EMAIL,
    val discordRoute: DiscordRoute? = null,
    val discordColor: Int? = null,
) {
    init {
        val discord = channel == OwnerAlertChannel.DISCORD
        require(discord == (discordRoute != null)) { "discordRoute must be set only for DISCORD rows." }
        require(discord == (discordColor != null)) { "discordColor must be set only for DISCORD rows." }
    }
}

/** 실계정 순손익 알림 메일의 제목·본문 (design §4.3). 숫자·시각 표기는 locale 과 무관하다. */
object AccountPnlMailContent {
    private const val HOURLY_HEADER = "정시 보고 — 1% 알림 기준과 무관하게 매시간 발송된다."
    private val HOUR_FORMAT = DateTimeFormatter.ofPattern("HH")

    fun profit(pnl: AccountNetPnl, zone: ZoneId): AccountPnlMail {
        val subject = "[김프 알림] 순수익률 ${percent(pnl.netPercent)} · ${krw(pnl.netKrw)}"
        return AccountPnlMail(eventKey("profit", pnl.calculatedAt), subject, profitLines(pnl, zone).joinToString("\n"))
    }

    /**
     * 정시 보고. 1% 알림 기준과 무관하게 시간대 버킷(`zone` 의 정시)당 한 통이며, 버킷 시작 시각이 eventKey 라
     * outbox 의 event_key UNIQUE 가 재시작·회차 반복에서도 중복을 막는다.
     */
    fun hourly(pnl: AccountNetPnl, zone: ZoneId): AccountPnlMail {
        val bucket = pnl.calculatedAt.atZone(zone).truncatedTo(ChronoUnit.HOURS)
        val subject = "[김프 알림] 정시 보고 ${HOUR_FORMAT.format(bucket)}:00 · 순수익률 ${percent(pnl.netPercent)} · ${krw(pnl.netKrw)}"
        val body = listOf(HOURLY_HEADER, "") + profitLines(pnl, zone)
        return AccountPnlMail(eventKey("hourly", bucket.toInstant()), subject, body.joinToString("\n"))
    }

    private fun profitLines(pnl: AccountNetPnl, zone: ZoneId): List<String> {
        val korea = pnl.position.korea
        val foreign = pnl.position.foreign
        val manual = foreign.positionSource == ForeignPerpPositionSource.MANUAL_ENV
        return buildList {
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
                add("  - 바이낸스: 진입 수수료(바이낸스) 미반영(${if (manual) "수동 미입력" else "이력 없음"})")
            } else {
                add("  - 바이낸스: ${costUsdt(commission)} (${cost(commission.multiply(pnl.fxRate))})${if (manual) " 수동 입력" else ""}")
            }
            val funding = foreign.fundingUsdt
            if (funding == null) {
                add("누적 펀딩비: ${krwOrZero(pnl.fundingKrw)}  펀딩비 미반영(${if (manual) "수동 미입력" else "이력 없음"})")
            } else {
                add("누적 펀딩비: ${krwOrZero(pnl.fundingKrw)} (${signedUsdt(funding)})${if (manual) " 수동 입력" else ""}")
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
            if (manual) add("바이낸스 포지션: 수동 입력(env)")
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
            add("조치 안내: ${AccountPnlFormat.RUNBOOK}")
        }
        return AccountPnlMail(eventKey("outage", now), "[김프 알림] 모니터링 중단 — $label", lines.joinToString("\n"))
    }

    fun recovered(now: Instant, zone: ZoneId): AccountPnlMail =
        AccountPnlMail(
            eventKey("recovered", now),
            "[김프 알림] 모니터링 복구",
            "실계정 순손익 모니터링이 복구되었습니다.\n복구 시각: ${time(now, zone)}",
        )

    private const val FOREIGN_PRICE_SCALE = 4

    /**
     * outbox 중복 제거 키이자 SMTP Message-ID 식별자다. 메일 발송기의 deliveryId 형식 `[A-Za-z0-9._-]{1,128}` 을
     * 지켜야 한다 — 어긋나면 발송이 매번 예외로 끝나 5회 뒤 FAILED 가 된다.
     */
    private fun eventKey(kind: String, at: Instant): String = "account-pnl-$kind-${at.toEpochMilli()}"
}
