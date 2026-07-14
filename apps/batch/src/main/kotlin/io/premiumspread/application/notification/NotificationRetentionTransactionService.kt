package io.premiumspread.application.notification

import io.premiumspread.domain.notification.NotificationDeliveryPort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class NotificationRetentionTransactionService(
    private val deliveryPort: NotificationDeliveryPort,
) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun scrubSentPii(sentBefore: Instant, scrubbedAt: Instant, limit: Int): Int =
        deliveryPort.scrubSentPii(sentBefore, scrubbedAt, limit)
}
