package io.premiumspread.interfaces.scheduling

import io.premiumspread.config.BatchSchedulingConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Import

class SchedulingDisabledContextTest {
    private val contextRunner =
        ApplicationContextRunner()
            .withUserConfiguration(SchedulingBoundaryConfiguration::class.java)
            .withPropertyValues("batch.scheduling.enabled=false")

    @Test
    fun `disabled scheduling registers no scheduler or scheduling infrastructure`() {
        contextRunner.run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).doesNotHaveBean(BatchSchedulingConfiguration::class.java)
            SCHEDULERS.forEach { scheduler ->
                assertThat(context.getBeansOfType(scheduler)).isEmpty()
            }
        }
    }

    @Test
    fun `legacy scheduling enabled false remains a supported disable alias`() {
        ApplicationContextRunner()
            .withUserConfiguration(SchedulingBoundaryConfiguration::class.java)
            // application.yml의 canonical 기본값이 true로 존재해도 legacy false가 비활성화를 우선해야 한다.
            .withPropertyValues(
                "batch.scheduling.enabled=true",
                "scheduling.enabled=false",
            )
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).doesNotHaveBean(BatchSchedulingConfiguration::class.java)
                SCHEDULERS.forEach { scheduler -> assertThat(context.getBeansOfType(scheduler)).isEmpty() }
            }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(BatchSchedulingProperties::class)
    @Import(
        BatchSchedulingConfiguration::class,
        BinanceFlushScheduler::class,
        BithumbFlushScheduler::class,
        ExchangeRateScheduler::class,
        PremiumAggregationScheduler::class,
        PremiumScheduler::class,
        TickerAggregationScheduler::class,
    )
    class SchedulingBoundaryConfiguration

    private companion object {
        val SCHEDULERS =
            listOf(
                BinanceFlushScheduler::class.java,
                BithumbFlushScheduler::class.java,
                ExchangeRateScheduler::class.java,
                PremiumAggregationScheduler::class.java,
                PremiumScheduler::class.java,
                TickerAggregationScheduler::class.java,
            )
    }
}
