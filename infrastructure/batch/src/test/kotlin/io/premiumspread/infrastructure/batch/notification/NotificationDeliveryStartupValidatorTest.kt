package io.premiumspread.infrastructure.batch.notification

import io.premiumspread.email.SmtpConnectionProperties
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration

class NotificationDeliveryStartupValidatorTest {
    @Test
    fun `configured SMTP operation timeout budget must fit hard deadline`() {
        val smtp = smtp(connect = 3, read = 10, write = 10)

        assertThatThrownBy {
            NotificationDeliveryStartupValidator(smtp, Duration.ofSeconds(10))
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("configured SMTP")
    }

    @Test
    fun `configured SMTP operation timeout budget equal to hard deadline is valid`() {
        val smtp = smtp(connect = 3, read = 10, write = 10)

        assertThatCode {
            NotificationDeliveryStartupValidator(smtp, Duration.ofSeconds(23))
        }.doesNotThrowAnyException()
    }

    private fun smtp(connect: Long, read: Long, write: Long) = SmtpConnectionProperties(
        host = "smtp.example.com",
        port = 587,
        username = "user",
        password = "secret",
        connectTimeout = Duration.ofSeconds(connect),
        readTimeout = Duration.ofSeconds(read),
        writeTimeout = Duration.ofSeconds(write),
    )
}
