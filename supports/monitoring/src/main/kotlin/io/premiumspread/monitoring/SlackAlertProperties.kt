package io.premiumspread.monitoring

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "alert.slack")
data class SlackAlertProperties(
    val webhookUrl: String = "",
)
