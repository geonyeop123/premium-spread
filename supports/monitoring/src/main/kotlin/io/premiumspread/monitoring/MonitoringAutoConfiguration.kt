package io.premiumspread.monitoring

import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.config.MeterFilter
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.actuate.autoconfigure.availability.AvailabilityHealthContributorAutoConfiguration
import org.springframework.boot.availability.ApplicationAvailability
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.core.env.Environment
import org.springframework.data.redis.core.StringRedisTemplate
import javax.sql.DataSource

/**
 * 모니터링 자동 설정
 */
@AutoConfiguration(before = [AvailabilityHealthContributorAutoConfiguration::class])
@Import(MetricsConfig::class)
@EnableConfigurationProperties(SlackAlertProperties::class)
class MonitoringAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = ["readinessStateHealthIndicator"])
    fun readinessStateHealthIndicator(
        availability: ApplicationAvailability,
        environment: Environment,
        dataSources: ObjectProvider<DataSource>,
        redisTemplates: ObjectProvider<StringRedisTemplate>,
        ingestionHealth: ObjectProvider<CriticalIngestionHealth>,
    ) = DependencyReadinessHealthIndicator(
        availability,
        environment,
        dataSources,
        redisTemplates,
        ingestionHealth,
    )

    @Bean
    fun boundedMetricTagFilter(): MeterFilter = BoundedMetricTagFilter()

    @Bean
    fun httpUriCardinalityFilter(): MeterFilter = MeterFilter.maximumAllowableTags(
        OperationalMetricPolicy.Names.HTTP_REQUESTS,
        "uri",
        100,
        MeterFilter.deny(),
    )

    @Bean
    @ConditionalOnProperty(name = ["alert.slack.webhook-url"])
    fun slackAlertService(
        properties: SlackAlertProperties,
        objectMapper: ObjectMapper,
    ): AlertService = SlackAlertService(properties, objectMapper)

    @Bean
    @ConditionalOnMissingBean(AlertService::class)
    fun logAlertService(): AlertService = LogAlertService()

    @Bean
    fun applicationHealthIndicator(): ApplicationHealthIndicator = ApplicationHealthIndicator()

    @Bean
    @ConditionalOnBean(MeterRegistry::class)
    fun premiumMetrics(meterRegistry: MeterRegistry): PremiumMetrics = PremiumMetrics(meterRegistry)
}
