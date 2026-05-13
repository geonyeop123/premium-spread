package io.premiumspread.application.notification

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(prefix = "alert.email", name = ["from"])
class PremiumThresholdNotificationListener(
    private val service: PremiumThresholdNotificationService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Async("notificationExecutor")
    @EventListener
    fun on(event: PremiumUpdatedEvent) {
        try {
            service.process(event)
        } catch (e: Exception) {
            log.error(
                "알림 리스너 처리 실패 - symbol={}, rate={}: {}",
                event.symbol,
                event.premiumRate,
                e.message,
                e,
            )
        }
    }
}
