package io.premiumspread.interfaces.scheduling

import io.mockk.mockk
import io.premiumspread.application.job.aggregation.PremiumAggregationJob
import io.premiumspread.application.job.aggregation.TickerAggregationJob
import io.premiumspread.application.job.fx.FxIngestionJob
import io.premiumspread.application.job.premium.PremiumRealtimeJob
import io.premiumspread.application.job.ticker.BinanceTickerFlushJob
import io.premiumspread.application.job.ticker.BithumbTickerFlushJob
import io.premiumspread.application.job.tradeprep.TradePreparationEvaluationJob
import io.premiumspread.application.notification.NotificationPiiRetentionJob
import io.premiumspread.config.BatchSchedulingConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.FilterType
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

    /**
     * 반대 방향 — production 이 scheduler 를 **등록하는지** 본다.
     *
     * 위 두 테스트와 batch 통합 테스트는 scheduler 클래스를 `@Import`·`@Bean` 으로 손수 등록하므로
     * `@Component` 를 지워도 전부 green 이다(실측). 그러면 production context 가 scheduler 를 영영
     * 만들지 않아 Job 이 한 번도 돌지 않는 상태를 아무도 잡지 못한다. 그래서 이 테스트만은
     * production 과 같은 방식인 **component scan** 으로 빈을 만든다 — 어느 scheduler 든
     * `@Component` 가 빠지면 여기서 실패한다.
     *
     * `@EnableScheduling` 을 가진 `BatchSchedulingConfiguration` 은 `io.premiumspread.config` 라
     * 이 scan 밖이다. 빈은 만들어지되 timer 는 돌지 않는다.
     */
    @Test
    fun `enabled scheduling registers every scheduler through component scan`() {
        ApplicationContextRunner()
            .withUserConfiguration(SchedulingComponentScanConfiguration::class.java)
            .withPropertyValues("batch.scheduling.enabled=true")
            .run { context ->
                assertThat(context).hasNotFailed()
                SCHEDULERS.forEach { scheduler ->
                    assertThat(context.getBeansOfType(scheduler)).describedAs(scheduler.name).hasSize(1)
                }
            }
    }

    /**
     * production 배선(component scan)을 그대로 재현한다. scheduler 생성자가 요구하는 Job 은 이
     * 경계 밖이라 stub 으로 채운다 — 검증 대상은 "빈이 만들어지는가"이지 Job 의 동작이 아니다.
     * 같은 package 의 `@TestConfiguration` 은 scan 대상에서 제외한다(자기 자신과 위 boundary
     * 구성이 다시 등록돼 scheduler 빈이 둘이 되는 것을 막는다).
     */
    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(BatchSchedulingProperties::class)
    @ComponentScan(
        basePackages = ["io.premiumspread.interfaces.scheduling"],
        excludeFilters = [ComponentScan.Filter(type = FilterType.ANNOTATION, classes = [TestConfiguration::class])],
    )
    class SchedulingComponentScanConfiguration {
        @Bean
        fun fxIngestionJob(): FxIngestionJob = mockk()

        @Bean
        fun premiumRealtimeJob(): PremiumRealtimeJob = mockk()

        @Bean
        fun premiumAggregationJob(): PremiumAggregationJob = mockk()

        @Bean
        fun tickerAggregationJob(): TickerAggregationJob = mockk()

        @Bean
        fun binanceTickerFlushJob(): BinanceTickerFlushJob = mockk()

        @Bean
        fun bithumbTickerFlushJob(): BithumbTickerFlushJob = mockk()

        @Bean
        fun tradePreparationEvaluationJob(): TradePreparationEvaluationJob = mockk()

        @Bean
        fun notificationPiiRetentionJob(): NotificationPiiRetentionJob = mockk()
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
        TradePreparationEvaluationScheduler::class,
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
                TradePreparationEvaluationScheduler::class.java,
            )
    }
}
