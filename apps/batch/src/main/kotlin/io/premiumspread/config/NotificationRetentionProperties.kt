package io.premiumspread.config

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.time.Duration

/** 이메일 전송 on/off와 무관하게 기존 SENT row의 PII 보존 기간을 집행한다. */
@Validated
@ConfigurationProperties(prefix = "notification.delivery")
data class NotificationRetentionProperties(
    val scrubRetention: Duration = Duration.ofDays(30),
    val scrubInterval: Duration = Duration.ofHours(1),
    @field:Min(1) @field:Max(10_000) val scrubBatchSize: Int = 100,
    @field:Min(1) @field:Max(10_000) val scrubMaxBatchesPerRun: Int = 100,
) {
    init {
        require(scrubRetention.isPositive()) { "notification.delivery.scrub-retention must be positive" }
        require(scrubInterval.isPositive()) { "notification.delivery.scrub-interval must be positive" }
    }
}

private fun Duration.isPositive(): Boolean = !isZero && !isNegative
