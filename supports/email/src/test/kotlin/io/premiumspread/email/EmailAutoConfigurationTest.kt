package io.premiumspread.email

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.mail.javamail.JavaMailSenderImpl
import java.time.Duration

class EmailAutoConfigurationTest {
    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                EmailAutoConfiguration::class.java,
            ),
        )

    @Test
    fun `email is disabled by default`() {
        contextRunner.run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).doesNotHaveBean(EmailSender::class.java)
            assertThat(context).doesNotHaveBean(JavaMailSender::class.java)
        }
    }

    @Test
    fun `email sender is not registered when explicitly disabled`() {
        contextRunner
            .withPropertyValues(
                "notification.email.enabled=false",
                "notification.email.from=alert@example.com",
                "notification.email.smtp.host=smtp.example.com",
                "notification.email.smtp.port=587",
                "notification.email.smtp.username=user",
                "notification.email.smtp.password=secret",
            )
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).doesNotHaveBean(EmailSender::class.java)
                assertThat(context).doesNotHaveBean(JavaMailSender::class.java)
            }
    }

    @Test
    fun `enabled email fails fast when sender address is missing`() {
        contextRunner
            .withPropertyValues(
                "notification.email.enabled=true",
                "notification.email.smtp.host=smtp.example.com",
                "notification.email.smtp.port=587",
                "notification.email.smtp.username=user",
                "notification.email.smtp.password=secret",
            )
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure)
                    .hasMessageContaining("notification.email")
            }
    }

    @Test
    fun `enabled email fails fast when smtp credentials are missing`() {
        contextRunner
            .withPropertyValues(
                "notification.email.enabled=true",
                "notification.email.from=alert@example.com",
                "notification.email.smtp.host=smtp.example.com",
                "notification.email.smtp.port=587",
            )
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure)
                    .hasMessageContaining("notification.email.smtp")
            }
    }

    @Test
    fun `enabled email registers sender when required settings are valid`() {
        contextRunner
            .withPropertyValues(
                "notification.email.enabled=true",
                "notification.email.from=alert@example.com",
                "notification.email.smtp.host=smtp.example.com",
                "notification.email.smtp.port=587",
                "notification.email.smtp.username=user",
                "notification.email.smtp.password=secret",
            )
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).hasSingleBean(EmailSender::class.java)
                assertThat(context).hasSingleBean(JavaMailSender::class.java)
            }
    }

    @Test
    fun `SMTP connect read write timeout을 JavaMail에 전부 적용한다`() {
        contextRunner
            .withPropertyValues(
                "notification.email.enabled=true",
                "notification.email.from=alert@example.com",
                "notification.email.smtp.host=smtp.example.com",
                "notification.email.smtp.port=587",
                "notification.email.smtp.username=user",
                "notification.email.smtp.password=secret",
                "notification.email.smtp.connect-timeout=1500ms",
                "notification.email.smtp.read-timeout=7s",
                "notification.email.smtp.write-timeout=8s",
            )
            .run { context ->
                assertThat(context).hasNotFailed()
                val sender = context.getBean(JavaMailSender::class.java) as JavaMailSenderImpl
                assertThat(sender.javaMailProperties["mail.smtp.connectiontimeout"]).isEqualTo("1500")
                assertThat(sender.javaMailProperties["mail.smtp.timeout"]).isEqualTo("7000")
                assertThat(sender.javaMailProperties["mail.smtp.writetimeout"]).isEqualTo("8000")
            }
    }

    @Test
    fun `SMTP operation timeout이 worker hard deadline을 넘으면 거부한다`() {
        val properties = SmtpConnectionProperties(
            host = "smtp.example.com",
            port = 587,
            username = "user",
            password = "secret",
            connectTimeout = Duration.ofSeconds(3),
            readTimeout = Duration.ofSeconds(11),
            writeTimeout = Duration.ofSeconds(10),
        )

        assertThatThrownBy { properties.requireWithin(Duration.ofSeconds(10)) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("readTimeout")
            .hasMessageContaining("hardSendDeadline")
    }

    @Test
    fun `SMTP operation timeout이 worker hard deadline 이하이면 허용한다`() {
        val properties = SmtpConnectionProperties(
            host = "smtp.example.com",
            port = 587,
            username = "user",
            password = "secret",
            connectTimeout = Duration.ofSeconds(3),
            readTimeout = Duration.ofSeconds(10),
            writeTimeout = Duration.ofSeconds(10),
        )

        properties.requireWithin(Duration.ofSeconds(10))
    }
}
