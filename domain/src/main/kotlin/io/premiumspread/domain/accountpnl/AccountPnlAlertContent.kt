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
import java.time.temporal.ChronoUnit
import java.util.Locale

/** 실계정 순손익 알림 4종의 메일·Discord 문구. 문구 정본 message-spec.md "실계정 순손익", 값 출처 design §6.2. */
object AccountPnlAlertContent {
    const val COLOR_PROFIT: Int = 0x2ECC71
    const val COLOR_HOURLY: Int = 0x3498DB
    const val COLOR_OUTAGE: Int = 0xE74C3C
    const val COLOR_RECOVERED: Int = 0x95A5A6
    const val AUTHOR: String = "실계정 · BTC"

    private const val BITHUMB_AUTH_GUIDE = "빗썸 Open API key의 허용 IP가 현재 공인 IP와 같은지 확인하세요."
    private const val NOT_RECORDED_HISTORY = "이력 없음"
    private const val NOT_RECORDED_MANUAL = "수동 미입력"

    /** eventKey `account-pnl-profit-{calculatedAt ms}`. basis = pnl.calculatedAt. */
    fun profit(pnl: AccountNetPnl, zone: ZoneId): OwnerAlertMessage = pnlMessage(
        eventKey = eventKey("profit", pnl.calculatedAt),
        pnl = pnl,
        zone = zone,
        titleHead = "수익 알림",
        icon = "💰",
        color = COLOR_PROFIT,
        footerPrefix = "",
    )

    /** eventKey `account-pnl-hourly-{calculatedAt 의 zone 정시 ms}`. basis = pnl.calculatedAt. */
    fun hourly(pnl: AccountNetPnl, zone: ZoneId): OwnerAlertMessage {
        val bucket = pnl.calculatedAt.atZone(zone).truncatedTo(ChronoUnit.HOURS)
        val hour = "%02d".format(Locale.ROOT, bucket.hour)
        return pnlMessage(
            eventKey = eventKey("hourly", bucket.toInstant()),
            pnl = pnl,
            zone = zone,
            titleHead = "정시 보고 $hour:00",
            icon = "📊",
            color = COLOR_HOURLY,
            footerPrefix = "매시 정각 발송 · ",
        )
    }

    /** eventKey `account-pnl-outage-{now ms}`. basis = now. [마지막 성공] = notice.lastSuccessAt(null → "없음"), [중단 감지] = now. */
    fun outage(notice: OutageNotice.Outage, now: Instant, zone: ZoneId): OwnerAlertMessage {
        val label = label(notice.category)
        val threshold = AlertFormat.threshold(MonitoringOutagePolicy.THRESHOLD)
        val needsCheck = notice.category == AccountReadFailureCategory.BITHUMB_AUTH
        val mailBlocks = buildList {
            add(MailBlock.Text(listOf("실계정 순손익 계산이 $threshold 넘게 멈췄습니다.")))
            add(
                MailBlock.Section(
                    "상태",
                    listOf(
                        MailBlock.Pairs(
                            listOf(
                                MailPair("원인", label),
                                MailPair("마지막 성공", notice.lastSuccessAt?.let { AlertFormat.stamp(it, zone) } ?: "없음"),
                                MailPair("중단 감지", AlertFormat.stamp(now, zone)),
                            ),
                        ),
                    ),
                ),
            )
            if (needsCheck) add(MailBlock.Section("확인할 것", listOf(MailBlock.Text(listOf(BITHUMB_AUTH_GUIDE)))))
        }
        val rows = buildList {
            add(
                listOf(
                    EmbedCell("마지막 성공", notice.lastSuccessAt?.let { AlertFormat.clock(it, now, zone) } ?: "없음"),
                    EmbedCell("중단 감지", AlertFormat.clock(now, now, zone)),
                ),
            )
            if (needsCheck) add(listOf(EmbedCell("확인할 것", BITHUMB_AUTH_GUIDE)))
        }
        return OwnerAlertMessage(
            eventKey = eventKey("outage", now),
            mail = MailDocument("[실계정] 모니터링 중단 — $label", mailBlocks, now, zone),
            discord = EmbedDocument(
                author = AUTHOR,
                title = "🚨 모니터링 중단 — $label",
                color = COLOR_OUTAGE,
                rows = rows,
                footer = "$threshold 넘게 계산하지 못하면 알림",
                timestamp = now,
            ),
        )
    }

    /** eventKey `account-pnl-recovered-{now ms}`. 메일 basis 없음, embed timestamp = now. [복구 시각] = now. */
    fun recovered(now: Instant, zone: ZoneId): OwnerAlertMessage = OwnerAlertMessage(
        eventKey = eventKey("recovered", now),
        mail = MailDocument(
            subject = "[실계정] 모니터링 복구",
            blocks = listOf(
                MailBlock.Text(listOf("실계정 순손익 계산이 다시 정상입니다.")),
                MailBlock.Pairs(listOf(MailPair("복구 시각", AlertFormat.stamp(now, zone)))),
            ),
            basis = null,
            zone = zone,
        ),
        discord = EmbedDocument(
            author = AUTHOR,
            title = "✅ 모니터링 복구",
            color = COLOR_RECOVERED,
            rows = listOf(listOf(EmbedCell("복구 시각", AlertFormat.clock(now, now, zone)))),
            footer = null,
            timestamp = now,
        ),
    )

    /** 실패 범주 표기 (값 전부). */
    internal fun label(category: AccountReadFailureCategory): String = when (category) {
        AccountReadFailureCategory.BITHUMB_AUTH -> "빗썸 인증 실패"
        AccountReadFailureCategory.BINANCE_AUTH -> "바이낸스 인증 실패"
        AccountReadFailureCategory.NETWORK -> "네트워크·요청 한도 오류"
        AccountReadFailureCategory.STALE_MARKET_DATA -> "시세 지연"
        AccountReadFailureCategory.STALE_FX -> "환율 수집 지연"
        AccountReadFailureCategory.INSUFFICIENT_DEPTH -> "호가 깊이 부족"
        AccountReadFailureCategory.UNEXPECTED -> "기타 오류(로그 확인)"
    }

    @Suppress("LongParameterList")
    private fun pnlMessage(
        eventKey: String,
        pnl: AccountNetPnl,
        zone: ZoneId,
        titleHead: String,
        icon: String,
        color: Int,
        footerPrefix: String,
    ): OwnerAlertMessage {
        val b = AccountPnlBreakdown.of(pnl)
        val manual = b.foreign.source == ForeignPerpPositionSource.MANUAL_ENV
        val foreignReason = if (manual) NOT_RECORDED_MANUAL else NOT_RECORDED_HISTORY
        val unrecovered = { reason: String -> "미반영 ($reason)" }

        val koreaEntry = b.korea.entryFeeKrw?.let { AlertFormat.cost(it) } ?: unrecovered(NOT_RECORDED_HISTORY)
        val foreignEntry = b.foreign.entryFeeKrw?.let { krw ->
            "${AlertFormat.cost(krw)} (${AlertFormat.usdt2(b.foreign.entryFeeUsdt!!)})"
        } ?: unrecovered(foreignReason)
        val koreaExit = AlertFormat.cost(b.korea.exitFeeKrw)
        val foreignExit = "${AlertFormat.cost(b.foreign.exitFeeKrw)} (${AlertFormat.usdt2(b.foreign.exitFeeUsdt)})"
        val funding = b.fundingUsdt?.let { "${AlertFormat.krwOrZero(b.fundingKrw)} (${AlertFormat.signedUsdt2(it)})" }
            ?: unrecovered(foreignReason)
        val net = "${AlertFormat.krw(b.netKrw)} (${AlertFormat.percent(b.netPercent)})"
        val premium = "현김프 ${AlertFormat.premium(b.currentPremiumPercent)} · " +
            "매수김프 ${AlertFormat.premium(b.entryPremiumPercent)} · 차이 ${AlertFormat.percentPoint(b.premiumGapPercentPoint)}"
        val fxLine = "환율 ${AlertFormat.rate(b.fxRate)}원/USD"

        val koreaQty = AlertFormat.plain(b.korea.quantity)
        val foreignQty = AlertFormat.plain(b.foreign.quantity)
        val koreaTitle = "빗썸 현물 · $koreaQty BTC"
        val foreignTitle = "바이낸스 선물 숏 · $foreignQty BTC"
        val koreaPairs = listOf(
            MailPair("평단", AlertFormat.krwPrice(b.korea.averagePrice)),
            MailPair("현재가", AlertFormat.krwPrice(b.korea.currentPrice)),
            MailPair("손익", AlertFormat.krw(b.korea.pnlKrw)),
            MailPair("평가액", AlertFormat.krwPrice(b.korea.valueKrw)),
        )
        val foreignPnl = "${AlertFormat.krw(b.foreign.pnlKrw)} (${AlertFormat.signedUsdt2(b.foreign.pnlUsdt)})"
        val foreignValue = "${AlertFormat.krwPrice(b.foreign.valueKrw)} (${AlertFormat.usdt2(b.foreign.valueUsdt)})"
        val foreignPairs = listOf(
            MailPair("평단", AlertFormat.usdtPrice(b.foreign.entryPrice)),
            MailPair("현재가", AlertFormat.usdtPrice(b.foreign.currentPrice)),
            MailPair("손익", foreignPnl),
            MailPair("평가액", foreignValue),
        )
        val fee = AlertFormat.cost(b.feeTotalKrw)
        val gross = AlertFormat.krw(b.grossKrw)

        val mailBlocks = listOf(
            MailBlock.Section("김프", listOf(MailBlock.Text(listOf(premium, fxLine)))),
            MailBlock.Section("빗썸 현물 ($koreaQty BTC)", listOf(MailBlock.Pairs(koreaPairs))),
            MailBlock.Section("바이낸스 선물 숏 ($foreignQty BTC)", listOf(MailBlock.Pairs(foreignPairs))),
            MailBlock.Section(
                "합산",
                listOf(
                    MailBlock.Pairs(
                        listOf(
                            MailPair("가격 차익", gross),
                            MailPair(
                                "수수료",
                                fee,
                                listOf(
                                    MailPair("빗썸 진입", koreaEntry),
                                    MailPair("바이낸스 진입", foreignEntry),
                                    MailPair("빗썸 정리 예상", koreaExit),
                                    MailPair("바이낸스 정리 예상", foreignExit),
                                ),
                            ),
                            MailPair("펀딩비", funding),
                            MailPair("순손익", net),
                        ),
                    ),
                ),
            ),
        )
        val rows = listOf(
            listOf(
                EmbedCell("현김프", AlertFormat.premium(b.currentPremiumPercent)),
                EmbedCell("매수김프", AlertFormat.premium(b.entryPremiumPercent)),
                EmbedCell("차이", AlertFormat.percentPoint(b.premiumGapPercentPoint)),
            ),
            listOf(
                EmbedCell(koreaTitle, koreaPairs.joinToString("\n") { "${it.label} ${it.value}" }),
                EmbedCell(foreignTitle, foreignPairs.joinToString("\n") { "${it.label} ${it.value}" }),
            ),
            listOf(EmbedCell("가격 차익", gross), EmbedCell("수수료 합계", fee), EmbedCell("펀딩비", funding)),
            listOf(
                EmbedCell("진입 수수료", "빗썸 $koreaEntry\n바이낸스 $foreignEntry"),
                EmbedCell("정리 수수료(예상)", "빗썸 $koreaExit\n바이낸스 $foreignExit"),
            ),
            listOf(EmbedCell("순손익", "**$net**")),
        )
        val headline = "$titleHead ${AlertFormat.percent(b.netPercent)} ${AlertFormat.krw(b.netKrw)}"
        return OwnerAlertMessage(
            eventKey = eventKey,
            mail = MailDocument("[실계정] $headline", mailBlocks, pnl.calculatedAt, zone),
            discord = EmbedDocument(
                author = AUTHOR,
                title = "$icon $headline",
                color = color,
                rows = rows,
                footer = "${footerPrefix}환율 ${AlertFormat.rate(b.fxRate)}원/USD",
                timestamp = pnl.calculatedAt,
            ),
        )
    }

    /**
     * outbox 중복 제거 키이자 SMTP Message-ID 식별자다. 메일 발송기의 deliveryId 형식 `[A-Za-z0-9._-]{1,128}` 을
     * 지켜야 한다 — 어긋나면 발송이 매번 예외로 끝나 5회 뒤 FAILED 가 된다.
     */
    private fun eventKey(kind: String, at: Instant): String = "account-pnl-$kind-${at.toEpochMilli()}"
}
