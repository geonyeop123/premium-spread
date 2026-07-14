package io.premiumspread.application.notification

import io.premiumspread.PremiumSpreadBatchApplication
import io.premiumspread.email.EmailAutoConfiguration
import io.premiumspread.email.EmailSender
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.mail.MailSenderAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.mail.javamail.JavaMailSender

class EmailNotificationActivationContextTest {
    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                EmailAutoConfiguration::class.java,
            ),
        )
        .withUserConfiguration(
            PremiumThresholdNotificationService::class.java,
            PremiumThresholdNotificationListener::class.java,
        )

    @Test
    fun `disabled notification does not register listener service or sender`() {
        contextRunner
            .withPropertyValues("notification.email.enabled=false")
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).doesNotHaveBean(PremiumThresholdNotificationListener::class.java)
                assertThat(context).doesNotHaveBean(PremiumThresholdNotificationService::class.java)
                assertThat(context).doesNotHaveBean(EmailSender::class.java)
                assertThat(context).doesNotHaveBean(JavaMailSender::class.java)
            }
    }

    @Test
    fun `batch application excludes Boot default mail sender auto configuration`() {
        val annotation = PremiumSpreadBatchApplication::class.java
            .getAnnotation(SpringBootApplication::class.java)

        assertThat(annotation.exclude)
            .contains(MailSenderAutoConfiguration::class)
    }
}
