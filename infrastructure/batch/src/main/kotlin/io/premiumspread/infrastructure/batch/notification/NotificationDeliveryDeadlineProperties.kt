package io.premiumspread.infrastructure.batch.notification

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.time.Duration

/** SMTP adapter가 application worker와 공유하는 hard deadline의 infrastructure-side typed view. */
@Validated
@ConfigurationProperties(prefix = "notification.delivery")
data class NotificationDeliveryDeadlineProperties(val hardSendDeadline: Duration = Duration.ofSeconds(30)) {
    init {
        require(!hardSendDeadline.isZero && !hardSendDeadline.isNegative) {
            "notification.delivery.hard-send-deadline must be positive"
        }
    }
}
