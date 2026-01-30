package io.premiumspread.monitoring

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import

/**
 * 모니터링 자동 설정
 */
@AutoConfiguration
@Import(MetricsConfig::class)
class MonitoringAutoConfiguration {

    @Bean
    fun alertService(): AlertService = AlertService()

    @Bean
    fun applicationHealthIndicator(): ApplicationHealthIndicator = ApplicationHealthIndicator()

    @Bean
    @ConditionalOnBean(MeterRegistry::class)
    fun premiumMetrics(meterRegistry: MeterRegistry): PremiumMetrics = PremiumMetrics(meterRegistry)
}
