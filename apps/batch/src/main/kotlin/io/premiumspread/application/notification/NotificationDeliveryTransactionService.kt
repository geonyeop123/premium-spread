package io.premiumspread.application.notification

import io.premiumspread.domain.notification.ClaimedNotificationDelivery
import io.premiumspread.domain.notification.DeliveryTransition
import io.premiumspread.domain.notification.NewNotificationDelivery
import io.premiumspread.domain.notification.NotificationDeliveryEnqueueResult
import io.premiumspread.domain.notification.NotificationDeliveryPort
import io.premiumspread.domain.notification.NotificationDeliveryStaleOwnershipObserver
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/** SMTP 같은 외부 I/O와 DB transaction이 섞이지 않도록 짧은 상태 전이만 소유한다. */
@Service
@ConditionalOnProperty(prefix = "notification.email", name = ["enabled"], havingValue = "true")
class NotificationDeliveryTransactionService(
    private val deliveryPort: NotificationDeliveryPort,
    private val staleOwnershipObserver: NotificationDeliveryStaleOwnershipObserver,
) {
    @Transactional
    fun enqueue(delivery: NewNotificationDelivery): NotificationDeliveryEnqueueResult =
        deliveryPort.enqueue(delivery)

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun claimReady(now: Instant, limit: Int, workerId: String): List<ClaimedNotificationDelivery> =
        deliveryPort.claimReady(now, limit, workerId)

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun markSent(delivery: ClaimedNotificationDelivery, sentAt: Instant): Boolean =
        deliveryPort.markSent(delivery.claim, sentAt).also { updated ->
            if (!updated) staleOwnershipObserver.record(DeliveryTransition.MARK_SENT)
        }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun scheduleRetry(
        delivery: ClaimedNotificationDelivery,
        nextAttemptAt: Instant,
        reason: String,
        updatedAt: Instant,
    ): Boolean = deliveryPort.scheduleRetry(delivery.claim, nextAttemptAt, reason, updatedAt).also { updated ->
        if (!updated) staleOwnershipObserver.record(DeliveryTransition.SCHEDULE_RETRY)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun markFailed(delivery: ClaimedNotificationDelivery, reason: String, failedAt: Instant): Boolean =
        deliveryPort.markFailed(delivery.claim, reason, failedAt).also { updated ->
            if (!updated) staleOwnershipObserver.record(DeliveryTransition.MARK_FAILED)
        }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun recoverStale(staleBefore: Instant, recoveredAt: Instant): Int =
        deliveryPort.recoverStale(staleBefore, recoveredAt)

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun scrubSentPii(sentBefore: Instant, scrubbedAt: Instant, limit: Int): Int =
        deliveryPort.scrubSentPii(sentBefore, scrubbedAt, limit)

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun redriveFailed(
        deliveryId: String,
        actor: String,
        reason: String,
        redrivenAt: Instant,
    ): Boolean =
        deliveryPort.redriveFailed(deliveryId, actor, reason, redrivenAt)
}
