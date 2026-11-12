package io.premiumspread.domain.accountpnl

import io.premiumspread.domain.alertmessage.AlertFormat
import io.premiumspread.domain.alertmessage.EmbedCell
import io.premiumspread.domain.alertmessage.EmbedDocument
import io.premiumspread.domain.alertmessage.MailBlock
import io.premiumspread.domain.alertmessage.MailDocument
import io.premiumspread.domain.alertmessage.MailPair
import io.premiumspread.domain.alertmessage.OwnerAlertMessage
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId

/**
 * 청산 근접 단계·완화 알림(#141)의 메일·Discord 문구. 문구 정본 message-spec.md "계정 알림 — 청산 근접", design D8.
 * 실계정 가격(마크가·청산가·진입가)은 담고 수량·증거금 금액은 담지 않는다.
 */
object LiquidationProximityContent {
    const val COLOR_PROXIMITY: Int = 0xE74C3C
    const val COLOR_EASED: Int = 0x95A5A6

    private const val MAX_CELLS_PER_ROW = 3
    private const val UNKNOWN = "확인 불가"
    private const val PROXIMITY_FOOTER = "진입가→청산가 진행률 · 50%부터 10% 단위 · 같은 경계는 한 단계 내려간 뒤 다시 알림"
    private const val EASED_FOOTER = "알린 경계보다 한 단계 아래로 내려가 다시 알릴 수 있습니다"

    /** `account-pnl-liquidation-{level}-{observedAt ms}`. */
    fun eventKey(alert: AccountAlert.LiquidationProximity): String =
        "account-pnl-liquidation-${alert.level}-${alert.observedAt.toEpochMilli()}"

    /** `account-pnl-liquidation-eased-{armedLevel}-{observedAt ms}`. */
    fun eventKey(alert: AccountAlert.LiquidationEased): String =
        "account-pnl-liquidation-eased-${alert.armedLevel}-${alert.observedAt.toEpochMilli()}"

    fun message(alert: AccountAlert.LiquidationProximity, zone: ZoneId): OwnerAlertMessage {
        val values = Values(
            alert.progressPercent,
            alert.side,
            alert.entryPrice,
            alert.markPrice,
            alert.liquidationPrice,
            alert.config,
        )
        val headline = "청산 근접 ${alert.level}%"
        val intro = "바이낸스 선물 ${values.sideName} 포지션이 진입가에서 청산가까지 ${alert.level}% 경계를 넘었습니다."
        return render(
            Frame(eventKey(alert), headline, "🚨 ", COLOR_PROXIMITY, alert.observedAt),
            intro = intro,
            guide = "같은 경계는 한 단계 내려간 뒤 다시 알립니다.",
            footer = PROXIMITY_FOOTER,
            cells = values.cells(),
            zone = zone,
        )
    }

    fun message(alert: AccountAlert.LiquidationEased, zone: ZoneId): OwnerAlertMessage {
        val values = Values(
            alert.progressPercent,
            alert.side,
            alert.entryPrice,
            alert.markPrice,
            alert.liquidationPrice,
            alert.config,
        )
        val previous = "${alert.previousLevel}%"
        val intro = "바이낸스 선물 ${values.sideName} 포지션의 진행률이 직전 알림 경계 ${previous}보다 한 단계 아래로 내려갔습니다."
        return render(
            Frame(eventKey(alert), "청산 위험 완화 ${values.progress}", "✅ ", COLOR_EASED, alert.observedAt),
            intro = intro,
            guide = "$EASED_FOOTER.",
            footer = EASED_FOOTER,
            cells = listOf(EmbedCell("직전 알림 경계", previous)) + values.cells(),
            zone = zone,
        )
    }

    private fun render(
        frame: Frame,
        intro: String,
        guide: String,
        footer: String,
        cells: List<EmbedCell>,
        zone: ZoneId,
    ): OwnerAlertMessage = OwnerAlertMessage(
        eventKey = frame.eventKey,
        mail = MailDocument(
            subject = "[실계정] ${frame.headline}",
            blocks = listOf(
                MailBlock.Text(listOf(intro)),
                MailBlock.Pairs(cells.map { MailPair(it.name, it.value) }),
                MailBlock.Text(listOf(guide)),
            ),
            basis = frame.observedAt,
            zone = zone,
        ),
        discord = EmbedDocument(
            author = AccountPnlAlertContent.AUTHOR,
            title = frame.emoji + frame.headline,
            color = frame.color,
            rows = cells.chunked(MAX_CELLS_PER_ROW),
            footer = footer,
            timestamp = frame.observedAt,
        ),
    )

    private data class Frame(
        val eventKey: String,
        val headline: String,
        val emoji: String,
        val color: Int,
        val observedAt: Instant,
    )

    /** 두 알림 공통 칸. data class 가 아니라 toString 에 가격이 나오지 않는다. */
    private class Values(
        progressPercent: BigDecimal,
        side: PerpSide,
        private val entry: BigDecimal,
        private val mark: BigDecimal,
        private val liquidation: BigDecimal,
        private val config: ForeignPerpSymbolConfig?,
    ) {
        /** 진행률 소수 1자리 — `85.2%`. 판정(절사)과 같게 먼저 절사한다: 59.96 이 "60.0%" 로 50% 제목 아래 보이지 않게 한다. */
        val progress: String = AlertFormat.format("0.0", progressPercent.setScale(1, RoundingMode.DOWN)) + "%"
        val sideName: String = if (side == PerpSide.SHORT) "숏" else "롱"

        fun cells(): List<EmbedCell> = listOf(
            EmbedCell("진행률", progress),
            EmbedCell("현재 마크가", AlertFormat.usdtPrice(mark)),
            EmbedCell("청산가", AlertFormat.usdtPrice(liquidation)),
            EmbedCell("진입가", AlertFormat.usdtPrice(entry)),
            EmbedCell("레버리지", config?.let { "${it.leverage}x" } ?: UNKNOWN),
            EmbedCell("마진 모드", config?.let { marginType(it.marginType) } ?: UNKNOWN),
        )

        private fun marginType(type: ForeignPerpMarginType): String = when (type) {
            ForeignPerpMarginType.CROSSED -> "교차"
            ForeignPerpMarginType.ISOLATED -> "격리"
        }
    }
}
