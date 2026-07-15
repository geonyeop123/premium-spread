package io.premiumspread.email

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.time.Duration

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
    val connectTimeout: Duration = Duration.ofSeconds(3),
    val readTimeout: Duration = Duration.ofSeconds(10),
    val writeTimeout: Duration = Duration.ofSeconds(10),
) {
    init {
        require(connectTimeout.isPositive()) { "SMTP connectTimeout must be positive" }
        require(readTimeout.isPositive()) { "SMTP readTimeout must be positive" }
        require(writeTimeout.isPositive()) { "SMTP writeTimeout must be positive" }
    }

    /**
     * JavaMail operation timeout이 durable delivery worker의 전체 발송 deadline을 넘지 않도록 검증한다.
     * 전체 deadline의 강제는 SMTP 호출을 소유한 worker가 담당한다.
     */
    fun requireWithin(hardSendDeadline: Duration) {
        require(hardSendDeadline.isPositive()) { "notification delivery hardSendDeadline must be positive" }
        require(connectTimeout <= hardSendDeadline) {
            "SMTP connectTimeout must not exceed notification delivery hardSendDeadline"
        }
        require(readTimeout <= hardSendDeadline) {
            "SMTP readTimeout must not exceed notification delivery hardSendDeadline"
        }
        require(writeTimeout <= hardSendDeadline) {
            "SMTP writeTimeout must not exceed notification delivery hardSendDeadline"
        }
    }
}

private fun Duration.isPositive() = !isZero && !isNegative
