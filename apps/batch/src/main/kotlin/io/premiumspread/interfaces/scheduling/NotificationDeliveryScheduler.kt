package io.premiumspread.interfaces.scheduling

import io.premiumspread.application.notification.NotificationDeliveryJob
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
@ConditionalOnBatchScheduling
@ConditionalOnProperty(prefix = "notification.email", name = ["enabled"], havingValue = "true")
class NotificationDeliveryScheduler(
    private val worker: NotificationDeliveryJob,
) {
    @Scheduled(fixedDelayString = "\${notification.delivery.poll-interval:5s}")
    fun poll() {
        worker.poll()
    }
}
