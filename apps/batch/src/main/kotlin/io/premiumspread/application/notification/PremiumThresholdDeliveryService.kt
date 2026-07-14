package io.premiumspread.application.notification

import io.premiumspread.config.NotificationDeliveryProperties
import io.premiumspread.domain.notification.ActiveNotificationSubscription
import io.premiumspread.domain.notification.ActiveNotificationSubscriptionPort
import io.premiumspread.domain.notification.NewNotificationDelivery
import io.premiumspread.domain.notification.NotificationEventKey
import io.premiumspread.domain.notification.ThresholdDirection
import io.premiumspread.domain.premium.PremiumSnapshot
import io.premiumspread.domain.premium.PremiumThresholdEvaluator
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

@Service
@ConditionalOnProperty(prefix = "notification.email", name = ["enabled"], havingValue = "true")
class PremiumThresholdDeliveryService(
    private val subscriptions: ActiveNotificationSubscriptionPort,
    private val transactions: NotificationDeliveryTransactionService,
    private val properties: NotificationDeliveryProperties,
    private val clock: Clock,
) : PremiumThresholdEvaluator {
    /** 활성 구독 조회와 unique event insert를 하나의 명시적 DB transaction에서 처리한다. */
    @Transactional
    override fun evaluate(snapshot: PremiumSnapshot) {
        val now = clock.instant()
        val windowStart = cooldownWindowStart(snapshot.observedAt, properties.cooldownWindow.toMillis())
        subscriptions.findActiveByPair(snapshot.pair)
            .asSequence()
            .filter { it.matches(snapshot.premiumRate) }
            .forEach { subscription ->
                transactions.enqueue(subscription.toDelivery(snapshot, windowStart, now))
                // unique event key 충돌(DUPLICATE)은 이미 enqueue된 정상적인 멱등 결과다.
            }
    }

    private fun cooldownWindowStart(observedAt: Instant, windowMillis: Long): Instant {
        val epochMillis = Math.floorDiv(observedAt.toEpochMilli(), windowMillis) * windowMillis
        return Instant.ofEpochMilli(epochMillis)
    }

    private fun ActiveNotificationSubscription.toDelivery(
        snapshot: PremiumSnapshot,
        windowStart: Instant,
        now: Instant,
    ): NewNotificationDelivery {
        val deliveryId = UUID.randomUUID().toString()
        val directionText = when (direction) {
            ThresholdDirection.ABOVE -> "${threshold.stripTrailingZeros().toPlainString()}% 이상 (ABOVE)"
            ThresholdDirection.BELOW -> "${threshold.stripTrailingZeros().toPlainString()}% 이하 (BELOW)"
        }
        val subject = "[premium-spread] ${symbol.uppercase()} 프리미엄 ${snapshot.premiumRate}% 도달"
        val payload = """
            안녕하세요, ${memberNickname}님.

            설정하신 알림 조건이 충족되었습니다.

            심볼: ${symbol.uppercase()}
            거래소 페어: ${pair.canonicalKey}
            조건: $directionText
            현재 프리미엄: ${snapshot.premiumRate}%
            관측 시각: ${snapshot.observedAt}

            --
            premium-spread
        """.trimIndent()
        return NewNotificationDelivery(
            deliveryId = deliveryId,
            subscriptionId = id,
            eventKey = NotificationEventKey.create(
                subscriptionId = id,
                subscriptionRevision = revision,
                pair = pair,
                direction = direction,
                threshold = threshold,
                cooldownWindow = properties.cooldownWindow,
                cooldownWindowStart = windowStart,
            ),
            recipientEmail = memberEmail,
            subject = subject,
            payload = payload,
            nextAttemptAt = now,
            createdAt = now,
        )
    }
}
