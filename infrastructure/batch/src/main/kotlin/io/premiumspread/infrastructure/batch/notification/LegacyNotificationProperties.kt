package io.premiumspread.infrastructure.batch.notification

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.time.ZoneId

@Validated
@ConfigurationProperties(prefix = "notification.email")
data class LegacyNotificationProperties(
    val zone: ZoneId = ZoneId.of("Asia/Seoul"),
)
