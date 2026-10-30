package io.premiumspread.application.notification

import io.premiumspread.config.NotificationDeliveryProperties
import io.premiumspread.domain.notification.ActiveNotificationSubscription
import io.premiumspread.domain.notification.ActiveNotificationSubscriptionPort
import io.premiumspread.domain.notification.NewNotificationDelivery
import io.premiumspread.domain.notification.NotificationEventKey
import io.premiumspread.domain.notification.PremiumThresholdMailContent
import io.premiumspread.domain.premium.PremiumSnapshot
import io.premiumspread.domain.premium.PremiumThresholdEvaluator
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/**
 * Service 스테레오타입 없이 Configuration 의 `@Bean` 으로 등록된다. 클래스 수준 `@Transactional` 은 Kotlin spring allopen 이
 * 클래스를 open 으로 만들어 CGLIB 프록시가 가능하게 한다(`evaluate` 가 유일한 public 진입점이다).
 */
@Transactional
// 실제 등록 조건은 NotificationDeliveryConfiguration 의 @Bean 이 소유한다.
// 이 애너테이션은 클래스를 직접 import 하는 경로(NotificationDisabledContextTest 등)용이다.
@ConditionalOnProperty(prefix = "notification.email", name = ["enabled"], havingValue = "true")
class PremiumThresholdDeliveryService(
    private val subscriptions: ActiveNotificationSubscriptionPort,
    private val transactions: NotificationDeliveryTransactionService,
    private val properties: NotificationDeliveryProperties,
    private val clock: Clock,
    private val zone: ZoneId = ZoneId.of("Asia/Seoul"),
) : PremiumThresholdEvaluator {
    /** 활성 구독 조회와 unique event insert를 하나의 명시적 DB transaction에서 처리한다. */
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
        val mail = PremiumThresholdMailContent.render(this, snapshot, zone)
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
            subject = mail.subject,
            payload = mail.body(),
            nextAttemptAt = now,
            createdAt = now,
        )
    }
}
