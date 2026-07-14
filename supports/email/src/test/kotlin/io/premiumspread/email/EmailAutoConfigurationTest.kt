package io.premiumspread.email

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.mail.javamail.JavaMailSender

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
}
