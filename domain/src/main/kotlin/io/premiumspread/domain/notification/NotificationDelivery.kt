package io.premiumspread.domain.notification

import io.premiumspread.domain.market.MarketPair
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

enum class DeliveryStatus {
    PENDING,
    PROCESSING,
    SENT,
    FAILED,
}

enum class NotificationDeliveryEnqueueResult {
    ENQUEUED,
    DUPLICATE,
}

enum class DeliveryTransition {
    MARK_SENT,
    SCHEDULE_RETRY,
    MARK_FAILED,
}

/** DB queue에 최초 저장할 불변 알림 payload다. */
data class NewNotificationDelivery(
    val deliveryId: String,
    val subscriptionId: Long,
    val eventKey: String,
    val recipientEmail: String,
    val subject: String,
    val payload: String,
    val nextAttemptAt: Instant,
    val createdAt: Instant,
)

/** PROCESSING 상태를 변경할 때 반드시 함께 검증할 소유권이다. */
data class DeliveryClaim(
    val id: Long,
    val lockedBy: String,
    val claimToken: String,
)

/** SMTP I/O 전에 commit된 PROCESSING delivery snapshot이다. */
data class ClaimedNotificationDelivery(
    val id: Long,
    val deliveryId: String,
    val subscriptionId: Long,
    val eventKey: String,
    val recipientEmail: String,
    val subject: String,
    val payload: String,
    val attemptCount: Int,
    val lockedBy: String,
    val claimToken: String,
    val createdAt: Instant,
) {
    val claim: DeliveryClaim = DeliveryClaim(id, lockedBy, claimToken)
}

/**
 * event 의미를 구성하는 모든 값을 canonical form으로 포함한다.
 * threshold는 scale 차이로 동일 조건이 다른 key가 되지 않도록 정규화한다.
 */
object NotificationEventKey {
    fun create(
        subscriptionId: Long,
        subscriptionRevision: Long,
        pair: MarketPair,
        direction: ThresholdDirection,
        threshold: BigDecimal,
        cooldownWindow: Duration,
        cooldownWindowStart: Instant,
    ): String {
        require(subscriptionId > 0) { "subscriptionId must be positive." }
        require(subscriptionRevision > 0) { "subscriptionRevision must be positive." }
        val cooldownMillis = cooldownWindow.toMillis()
        require(cooldownMillis >= 1) { "cooldownWindow must be at least 1ms." }
        val normalizedThreshold = if (threshold.compareTo(BigDecimal.ZERO) == 0) {
            "0"
        } else {
            threshold.stripTrailingZeros().toPlainString()
        }
        return "v2" +
            "|subscriptionId=$subscriptionId" +
            "|subscriptionRevision=$subscriptionRevision" +
            "|marketPair=${pair.canonicalKey}" +
            "|direction=${direction.name}" +
            "|threshold=$normalizedThreshold" +
            "|cooldownMillis=$cooldownMillis" +
            "|windowStart=$cooldownWindowStart"
    }
}

/** Transaction 경계는 application의 별도 transaction service가 소유한다. */
interface NotificationDeliveryPort {
    fun enqueue(delivery: NewNotificationDelivery): NotificationDeliveryEnqueueResult

    /** 선택된 row마다 서로 다른 UUID claimToken을 저장한 뒤 반환한다. */
    fun claimReady(now: Instant, limit: Int, workerId: String): List<ClaimedNotificationDelivery>

    fun markSent(claim: DeliveryClaim, sentAt: Instant): Boolean

    fun scheduleRetry(
        claim: DeliveryClaim,
        nextAttemptAt: Instant,
        reason: String,
        updatedAt: Instant,
    ): Boolean

    fun markFailed(claim: DeliveryClaim, reason: String, failedAt: Instant): Boolean

    fun recoverStale(staleBefore: Instant, recoveredAt: Instant): Int

    fun scrubSentPii(sentBefore: Instant, scrubbedAt: Instant, limit: Int): Int

    /** FAILED delivery를 PENDING으로 되돌리고 audit을 남긴다. 이후 poller가 새 claimToken을 발급한다. */
    fun redriveFailed(
        deliveryId: String,
        actor: String,
        reason: String,
        redrivenAt: Instant,
    ): Boolean
}

fun interface NotificationSender {
    fun deliver(delivery: ClaimedNotificationDelivery)
}

fun interface NotificationDeliveryStaleOwnershipObserver {
    fun record(transition: DeliveryTransition)
}
