package io.premiumspread.infrastructure.batch.notification

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.premiumspread.domain.notification.ClaimedNotificationDelivery
import io.premiumspread.domain.notification.DeliveryClaim
import io.premiumspread.domain.notification.DeliveryTransition
import io.premiumspread.domain.notification.NewNotificationDelivery
import io.premiumspread.domain.notification.NotificationDeliveryEnqueueResult
import io.premiumspread.domain.notification.NotificationDeliveryPort
import io.premiumspread.domain.notification.NotificationDeliveryStaleOwnershipObserver
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Instant

class NotificationDeliveryMetrics(
    registry: MeterRegistry?,
) : NotificationDeliveryStaleOwnershipObserver {
    private val counters: Map<String, Counter> = registry?.let { meterRegistry ->
        OUTCOMES.associateWith { outcome ->
            Counter.builder(METRIC_NAME)
                .description("Durable notification delivery lifecycle transitions")
                .tag("outcome", outcome)
                .register(meterRegistry)
        }
    }.orEmpty()

    fun increment(outcome: String, amount: Int = 1) {
        if (amount > 0) counters[outcome]?.increment(amount.toDouble())
    }

    override fun record(transition: DeliveryTransition) {
        when (transition) {
            DeliveryTransition.MARK_SENT,
            DeliveryTransition.SCHEDULE_RETRY,
            DeliveryTransition.MARK_FAILED,
            -> increment("stale_ownership")
        }
    }

    private companion object {
        const val METRIC_NAME = "premiumspread.notification.delivery.transitions"
        val OUTCOMES = setOf(
            "pending",
            "processing",
            "sent",
            "retry",
            "failed",
            "duplicate",
            "recovered",
            "scrubbed",
            "stale_ownership",
        )
    }
}

/** 공통 JDBC adapter에 bounded metric만 덧씌우며 delivery 식별자나 PII는 tag로 만들지 않는다. */
class ObservedNotificationDeliveryPort(
    private val delegate: NotificationDeliveryPort,
    private val metrics: NotificationDeliveryMetrics,
) : NotificationDeliveryPort {
    override fun enqueue(delivery: NewNotificationDelivery): NotificationDeliveryEnqueueResult =
        delegate.enqueue(delivery).also { result ->
            afterCommit {
                metrics.increment(
                    if (result == NotificationDeliveryEnqueueResult.ENQUEUED) "pending" else "duplicate",
                )
            }
        }

    override fun claimReady(now: Instant, limit: Int, workerId: String): List<ClaimedNotificationDelivery> =
        delegate.claimReady(now, limit, workerId).also { claimed ->
            afterCommit { metrics.increment("processing", claimed.size) }
        }

    override fun markSent(claim: DeliveryClaim, sentAt: Instant): Boolean =
        delegate.markSent(claim, sentAt).also { if (it) afterCommit { metrics.increment("sent") } }

    override fun scheduleRetry(
        claim: DeliveryClaim,
        nextAttemptAt: Instant,
        reason: String,
        updatedAt: Instant,
    ): Boolean = delegate.scheduleRetry(claim, nextAttemptAt, reason, updatedAt)
        .also { if (it) afterCommit { metrics.increment("retry") } }

    override fun markFailed(claim: DeliveryClaim, reason: String, failedAt: Instant): Boolean =
        delegate.markFailed(claim, reason, failedAt).also { if (it) afterCommit { metrics.increment("failed") } }

    override fun recoverStale(staleBefore: Instant, recoveredAt: Instant): Int =
        delegate.recoverStale(staleBefore, recoveredAt).also { recovered ->
            afterCommit { metrics.increment("recovered", recovered) }
        }

    override fun scrubSentPii(sentBefore: Instant, scrubbedAt: Instant, limit: Int): Int =
        delegate.scrubSentPii(sentBefore, scrubbedAt, limit).also { scrubbed ->
            afterCommit { metrics.increment("scrubbed", scrubbed) }
        }

    override fun redriveFailed(
        deliveryId: String,
        actor: String,
        reason: String,
        redrivenAt: Instant,
    ): Boolean =
        delegate.redriveFailed(deliveryId, actor, reason, redrivenAt)
            .also { updated ->
                if (updated) afterCommit { metrics.increment("pending") }
            }

    private fun afterCommit(action: () -> Unit) {
        if (
            TransactionSynchronizationManager.isActualTransactionActive() &&
            TransactionSynchronizationManager.isSynchronizationActive()
        ) {
            TransactionSynchronizationManager.registerSynchronization(
                object : TransactionSynchronization {
                    override fun afterCommit() = action()
                },
            )
        } else {
            action()
        }
    }
}
