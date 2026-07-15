package io.premiumspread.monitoring

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.convert.DurationUnit
import org.springframework.validation.annotation.Validated
import java.time.Duration
import java.time.temporal.ChronoUnit

@Validated
@ConfigurationProperties(prefix = "alert.slack")
data class SlackAlertProperties(
    val webhookUrl: String = "",
    @DurationUnit(ChronoUnit.MILLIS)
    val connectTimeout: Duration = Duration.ofSeconds(2),
    @DurationUnit(ChronoUnit.MILLIS)
    val readTimeout: Duration = Duration.ofSeconds(3),
) {
    init {
        require(connectTimeout.isPositive()) { "alert.slack.connect-timeout must be positive" }
        require(readTimeout.isPositive()) { "alert.slack.read-timeout must be positive" }
    }
}
