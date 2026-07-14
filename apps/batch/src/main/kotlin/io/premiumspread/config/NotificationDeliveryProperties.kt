package io.premiumspread.config

import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.time.Duration
import kotlin.math.ceil

@Validated
@ConfigurationProperties(prefix = "notification.delivery")
data class NotificationDeliveryProperties(
    val pollInterval: Duration = Duration.ofSeconds(5),
    @field:Min(1) @field:Max(1_000) val batchSize: Int = 10,
    @field:Min(1) @field:Max(100) val concurrency: Int = 2,
    val hardSendDeadline: Duration = Duration.ofSeconds(30),
    val dbQueueSafetyMargin: Duration = Duration.ofSeconds(30),
    val staleThreshold: Duration = Duration.ofMinutes(5),
    @field:Min(1) @field:Max(100) val maxAttempts: Int = 5,
    val retryDelays: List<Duration> = listOf(
        Duration.ofMinutes(1),
        Duration.ofMinutes(5),
        Duration.ofMinutes(30),
        Duration.ofHours(2),
    ),
    @field:DecimalMin("0.0") @field:DecimalMax("1.0") val retryJitterRatio: Double = 0.1,
    val cooldownWindow: Duration = Duration.ofHours(1),
) {
    init {
        require(pollInterval.isPositive()) { "notification.delivery.poll-interval must be positive" }
        require(hardSendDeadline.isPositive()) { "notification.delivery.hard-send-deadline must be positive" }
        require(!dbQueueSafetyMargin.isNegative) { "notification.delivery.db-queue-safety-margin must not be negative" }
        require(staleThreshold.isPositive()) { "notification.delivery.stale-threshold must be positive" }
        require(retryDelays.isNotEmpty()) { "notification.delivery.retry-delays must not be empty" }
        require(retryDelays.all(Duration::isPositive)) { "notification.delivery.retry-delays must be positive" }
        require(cooldownWindow.toMillis() >= 1) { "notification.delivery.cooldown-window must be at least 1ms" }
        val maximumClaimLifetime = hardSendDeadline
            .multipliedBy(ceil(batchSize.toDouble() / concurrency).toLong())
            .plus(dbQueueSafetyMargin)
        require(maximumClaimLifetime < staleThreshold) {
            "notification delivery claim lifetime ($maximumClaimLifetime) must be less than stale-threshold ($staleThreshold)"
        }
    }
}

private fun Duration.isPositive(): Boolean = !isZero && !isNegative
