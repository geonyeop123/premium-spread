package io.premiumspread.config

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "notification.email", name = ["enabled"], havingValue = "true")
@EnableConfigurationProperties(NotificationDeliveryProperties::class)
class NotificationDeliveryConfiguration
