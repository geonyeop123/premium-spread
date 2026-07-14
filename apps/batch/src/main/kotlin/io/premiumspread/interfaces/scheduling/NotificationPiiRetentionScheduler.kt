package io.premiumspread.interfaces.scheduling

import io.premiumspread.application.notification.NotificationPiiRetentionJob
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
@ConditionalOnBatchScheduling
class NotificationPiiRetentionScheduler(
    private val job: NotificationPiiRetentionJob,
) {
    @Scheduled(fixedDelayString = "\${notification.delivery.scrub-interval:1h}")
    fun scrubSentPii() {
        job.scrubSentPii()
    }
}
