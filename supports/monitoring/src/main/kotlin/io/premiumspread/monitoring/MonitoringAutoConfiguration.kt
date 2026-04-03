package io.premiumspread.monitoring

import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import

/**
 * 모니터링 자동 설정
 */
@AutoConfiguration
@Import(MetricsConfig::class)
@EnableConfigurationProperties(SlackAlertProperties::class)
class MonitoringAutoConfiguration {

    @Bean
    @ConditionalOnProperty(name = ["alert.slack.webhook-url"])
    fun slackAlertService(
        properties: SlackAlertProperties,
        objectMapper: ObjectMapper,
    ): AlertService = SlackAlertService(properties.webhookUrl, objectMapper)

    @Bean
    @ConditionalOnMissingBean(AlertService::class)
    fun logAlertService(): AlertService = LogAlertService()

    @Bean
    fun applicationHealthIndicator(): ApplicationHealthIndicator = ApplicationHealthIndicator()

    @Bean
    @ConditionalOnBean(MeterRegistry::class)
    fun premiumMetrics(meterRegistry: MeterRegistry): PremiumMetrics = PremiumMetrics(meterRegistry)
}
