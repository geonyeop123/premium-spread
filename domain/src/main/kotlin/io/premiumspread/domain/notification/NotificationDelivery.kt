package io.premiumspread.domain.notification

import java.time.Instant

enum class DeliveryStatus {
    PENDING,
    PROCESSING,
    SENT,
    FAILED,
}

data class NotificationDelivery(
    val deliveryId: String,
    val subscriptionId: Long,
    val recipient: String,
    val subject: String,
    val body: String,
    val status: DeliveryStatus,
    val attemptCount: Int,
    val nextAttemptAt: Instant,
    val createdAt: Instant,
)

interface NotificationDeliveryPort {
    fun enqueue(delivery: NotificationDelivery)

    fun findReady(now: Instant, limit: Int): List<NotificationDelivery>

    fun markSent(deliveryId: String, sentAt: Instant)

    fun scheduleRetry(deliveryId: String, nextAttemptAt: Instant, reason: String)
}

fun interface NotificationSender {
    fun deliver(delivery: NotificationDelivery)
}
