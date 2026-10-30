package io.premiumspread.domain.notification

import io.premiumspread.domain.alertmessage.AlertFormat
import io.premiumspread.domain.alertmessage.MailBlock
import io.premiumspread.domain.alertmessage.MailDocument
import io.premiumspread.domain.alertmessage.MailPair
import io.premiumspread.domain.premium.PremiumSnapshot
import java.time.ZoneId

/** 회원 김프 임계값 도달 메일. 문구 정본 message-spec.md "회원 알림", 값 출처 design §6.6. basis 없음, signature "premium-spread". */
object PremiumThresholdMailContent {
    private const val SIGNATURE = "premium-spread"

    fun render(subscription: ActiveNotificationSubscription, snapshot: PremiumSnapshot, zone: ZoneId): MailDocument {
        val symbol = subscription.symbol.uppercase()
        val side = when (subscription.direction) {
            ThresholdDirection.ABOVE -> "이상"
            ThresholdDirection.BELOW -> "이하"
        }
        return MailDocument(
            subject = "[김프 알림] $symbol 김프 ${AlertFormat.premium(snapshot.premiumRate)} 도달",
            blocks = listOf(
                MailBlock.Text(
                    listOf("안녕하세요, ${subscription.memberNickname}님.", "설정하신 김프 알림 조건에 도달했습니다."),
                ),
                MailBlock.Pairs(
                    listOf(
                        MailPair("종목", "$symbol (${AlertFormat.exchangePair(subscription.pair, " / ")})"),
                        MailPair("조건", "김프 ${AlertFormat.premium(subscription.threshold)} $side"),
                        MailPair("현재 김프", AlertFormat.premium(snapshot.premiumRate)),
                        MailPair("관측 시각", AlertFormat.stamp(snapshot.observedAt, zone)),
                    ),
                ),
            ),
            basis = null,
            zone = zone,
            signature = SIGNATURE,
        )
    }
}
