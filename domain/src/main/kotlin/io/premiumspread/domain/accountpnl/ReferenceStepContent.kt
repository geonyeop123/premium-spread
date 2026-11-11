package io.premiumspread.domain.accountpnl

import io.premiumspread.domain.alertmessage.AlertFormat
import io.premiumspread.domain.alertmessage.EmbedCell
import io.premiumspread.domain.alertmessage.EmbedDocument
import io.premiumspread.domain.alertmessage.MailBlock
import io.premiumspread.domain.alertmessage.MailDocument
import io.premiumspread.domain.alertmessage.MailPair
import io.premiumspread.domain.alertmessage.OwnerAlertMessage
import java.time.ZoneId

/**
 * 기준 포지션 단계 알림(#131)의 메일·Discord 문구. 문구 정본 message-spec.md "계정 알림 — 기준 포지션 단계".
 * 실계정 수량·평단은 담지 않고 김프(%)·차이(%p)·환율만 보여 준다.
 */
object ReferenceStepContent {
    private const val FLAT_PHASE = "포지션 없음 — 직전 포지션 기준"
    private const val HOLDING_PHASE = "보유 중"

    /** `account-pnl-reference-step-{up|down}-{level}-{observation.observedAt ms}`. */
    fun eventKey(alert: AccountAlert.ReferenceStep): String {
        val direction = if (alert.direction == SwingDirection.UP) "up" else "down"
        return "account-pnl-reference-step-$direction-${alert.level}-${alert.observation.observedAt.toEpochMilli()}"
    }

    fun message(alert: AccountAlert.ReferenceStep, zone: ZoneId): OwnerAlertMessage {
        val up = alert.direction == SwingDirection.UP
        val flat = alert.phase == ReferencePhase.FLAT
        val observation = alert.observation
        val boundary = AlertFormat.percentPoint(alert.boundary)
        val headline = when {
            flat && alert.level == 0 -> "직전 진입 수준 도달"
            flat -> "직전 포지션 대비 김프 $boundary"
            else -> "기준 대비 김프 $boundary"
        }
        val gap = AlertFormat.percentPoint(observation.premiumGapPercentPoint)
        val current = AlertFormat.premium(observation.currentPremiumPercent)
        val entry = AlertFormat.premium(observation.entryPremiumPercent)
        val fx = AlertFormat.rate(observation.fxRate)
        val phase = if (flat) FLAT_PHASE else HOLDING_PHASE
        val width = AlertFormat.format("0.00", alert.stepWidth)
        val intro = (if (flat) "직전 포지션" else "기준 포지션") +
            "보다 김프 차이가 $boundary 경계를 ${if (up) "넘어 올랐" else "넘어 내렸"}습니다."

        return OwnerAlertMessage(
            eventKey = eventKey(alert),
            mail = MailDocument(
                subject = "[실계정] $headline",
                blocks = listOf(
                    MailBlock.Text(listOf(intro)),
                    MailBlock.Pairs(
                        listOf(
                            MailPair("경계", boundary),
                            MailPair("현재 차이", gap),
                            MailPair("현재 김프", current),
                            MailPair("기준 매수김프", entry),
                            MailPair("환율", fx),
                            MailPair("국면", phase),
                        ),
                    ),
                    MailBlock.Text(listOf("같은 경계는 한 단계 움직인 뒤에 다시 알립니다.")),
                ),
                basis = observation.observedAt,
                zone = zone,
            ),
            discord = EmbedDocument(
                author = AccountPnlAlertContent.AUTHOR,
                title = (if (up) "📈 " else "📉 ") + headline,
                color = if (up) AccountAlertContent.COLOR_SWING_UP else AccountAlertContent.COLOR_SWING_DOWN,
                rows = listOf(
                    listOf(EmbedCell("경계", boundary), EmbedCell("현재 차이", gap), EmbedCell("현재 김프", current)),
                    listOf(EmbedCell("기준 매수김프", entry), EmbedCell("환율", fx)),
                    listOf(EmbedCell("국면", phase)),
                ),
                footer = "기준 ±$width%p 단계 · 같은 경계는 한 단계 움직인 뒤 다시 알림",
                timestamp = observation.observedAt,
            ),
        )
    }
}
