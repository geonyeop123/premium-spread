package io.premiumspread.config

import io.premiumspread.application.notification.NotificationDeliveryTransactionService
import io.premiumspread.application.notification.PremiumThresholdDeliveryService
import io.premiumspread.domain.notification.ActiveNotificationSubscriptionPort
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import java.time.ZoneId

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "notification.email", name = ["enabled"], havingValue = "true")
@EnableConfigurationProperties(NotificationDeliveryProperties::class)
class NotificationDeliveryConfiguration {
    /** 회원 메일 표시 zone 은 aggregation.zone 에서 온다(owner 알림 Configuration 과 같은 관례). */
    @Bean
    fun premiumThresholdDeliveryService(
        subscriptions: ActiveNotificationSubscriptionPort,
        transactions: NotificationDeliveryTransactionService,
        properties: NotificationDeliveryProperties,
        clock: Clock,
        @Value("\${aggregation.zone:Asia/Seoul}") zone: String,
    ): PremiumThresholdDeliveryService =
        PremiumThresholdDeliveryService(subscriptions, transactions, properties, clock, ZoneId.of(zone))
}
