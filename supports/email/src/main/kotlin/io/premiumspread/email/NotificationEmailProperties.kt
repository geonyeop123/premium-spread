package io.premiumspread.email

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

@Validated
@ConfigurationProperties(prefix = "notification.email")
data class NotificationEmailProperties(
    val enabled: Boolean = false,
    @field:NotBlank
    val from: String = "",
)

@Validated
@ConfigurationProperties(prefix = "notification.email.smtp")
data class SmtpConnectionProperties(
    @field:NotBlank
    val host: String = "",
    @field:Min(1)
    @field:Max(65_535)
    val port: Int = 0,
    @field:NotBlank
    val username: String = "",
    @field:NotBlank
    val password: String = "",
)
