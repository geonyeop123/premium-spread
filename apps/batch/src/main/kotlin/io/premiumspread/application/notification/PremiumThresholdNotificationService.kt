package io.premiumspread.application.notification

import io.premiumspread.cache.NotificationCooldownStore
import io.premiumspread.email.EmailDeliveryException
import io.premiumspread.email.EmailMessage
import io.premiumspread.email.EmailSender
import io.premiumspread.repository.ActiveSubscriptionReadRepository
import io.premiumspread.repository.ActiveSubscriptionView
import io.premiumspread.repository.ThresholdDirectionView
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import java.time.LocalDateTime

@Service
@ConditionalOnProperty(
    prefix = "notification.email",
    name = ["enabled"],
    havingValue = "true",
)
class PremiumThresholdNotificationService(
    private val readRepository: ActiveSubscriptionReadRepository,
    private val cooldownStore: NotificationCooldownStore,
    private val emailSender: EmailSender,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun process(event: PremiumUpdatedEvent) {
        val subscriptions = readRepository.findActiveBySymbol(event.symbol)
        for (sub in subscriptions) {
            if (!sub.matches(event.premiumRate)) continue
            if (!cooldownStore.tryAcquireCooldown(sub.id)) continue

            try {
                emailSender.send(buildMessage(sub, event))
            } catch (e: EmailDeliveryException) {
                cooldownStore.release(sub.id)
                log.error(
                    "구독 알림 발송 실패 - subscriptionId={}, email={}: {}",
                    sub.id,
                    sub.memberEmail,
                    e.message,
                    e,
                )
            }
        }
    }

    private fun buildMessage(sub: ActiveSubscriptionView, event: PremiumUpdatedEvent): EmailMessage {
        val directionText = when (sub.direction) {
            ThresholdDirectionView.ABOVE -> "${sub.threshold}% 이상 (ABOVE)"
            ThresholdDirectionView.BELOW -> "${sub.threshold}% 이하 (BELOW)"
        }
        val subject = "[premium-spread] ${event.symbol.uppercase()} 프리미엄 ${event.premiumRate}% 도달"
        val text = """
            안녕하세요, ${sub.memberNickname}님.

            설정하신 알림 조건이 충족되었습니다.

            심볼: ${sub.symbol.uppercase()}
            조건: $directionText
            현재 프리미엄: ${event.premiumRate}%
            발생 시각: ${LocalDateTime.now()}

            --
            premium-spread
        """.trimIndent()
        return EmailMessage(to = sub.memberEmail, subject = subject, text = text)
    }
}
