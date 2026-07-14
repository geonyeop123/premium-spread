package io.premiumspread.application.notification

import io.premiumspread.config.NotificationDeliveryConfiguration
import io.premiumspread.config.NotificationDeliveryProperties
import io.premiumspread.config.NotificationRetentionConfiguration
import io.premiumspread.config.NotificationRetentionProperties
import io.premiumspread.domain.notification.NotificationDeliveryPort
import io.premiumspread.interfaces.scheduling.NotificationDeliveryScheduler
import io.premiumspread.interfaces.scheduling.NotificationPiiRetentionScheduler
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Bean
import java.time.Clock

class NotificationDisabledContextTest {
    @Test
    fun `disabled email does not bind invalid delivery settings or create enqueue poller SMTP path`() {
        ApplicationContextRunner()
            .withUserConfiguration(DisabledNotificationBoundary::class.java)
            .withPropertyValues(
                "notification.email.enabled=false",
                "notification.delivery.batch-size=0",
                "notification.delivery.hard-send-deadline=-1s",
            )
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).doesNotHaveBean(NotificationDeliveryProperties::class.java)
                assertThat(context).doesNotHaveBean(PremiumThresholdDeliveryService::class.java)
                assertThat(context).doesNotHaveBean(NotificationDeliveryJob::class.java)
                assertThat(context).doesNotHaveBean(NotificationDeliveryScheduler::class.java)
                assertThat(context).hasSingleBean(NotificationRetentionProperties::class.java)
                assertThat(context).hasSingleBean(NotificationRetentionTransactionService::class.java)
                assertThat(context).hasSingleBean(NotificationPiiRetentionJob::class.java)
                assertThat(context).hasSingleBean(NotificationPiiRetentionScheduler::class.java)

                assertThat(context.getBean(NotificationPiiRetentionJob::class.java).scrubSentPii()).isZero()
                verify(exactly = 1) {
                    context.getBean(NotificationDeliveryPort::class.java).scrubSentPii(any(), any(), 100)
                }
            }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @Import(
        NotificationDeliveryConfiguration::class,
        NotificationRetentionConfiguration::class,
        PremiumThresholdDeliveryService::class,
        NotificationDeliveryTransactionService::class,
        NotificationDeliveryJob::class,
        NotificationRetentionTransactionService::class,
        NotificationPiiRetentionJob::class,
        NotificationDeliveryScheduler::class,
        NotificationPiiRetentionScheduler::class,
    )
    class DisabledNotificationBoundary {
        @Bean
        fun notificationDeliveryPort(): NotificationDeliveryPort = mockk(relaxed = true)

        @Bean
        fun clock(): Clock = Clock.systemUTC()
    }
}
