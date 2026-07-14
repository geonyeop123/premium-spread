package io.premiumspread.scheduler

import io.mockk.*
import io.premiumspread.application.common.JobConfig
import io.premiumspread.application.common.JobExecutor
import io.premiumspread.application.common.JobResult
import io.premiumspread.application.job.aggregation.AggregationWindowPolicy
import io.premiumspread.config.AggregationProperties
import io.premiumspread.cache.PremiumCacheService
import io.premiumspread.redis.AggregationTimeUnit
import io.premiumspread.domain.market.MarketPair
import io.premiumspread.domain.ticker.Symbol
import io.premiumspread.infrastructure.common.persistence.jdbc.premium.PremiumAggregation
import io.premiumspread.infrastructure.common.persistence.jdbc.premium.PremiumAggregationRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.scheduling.annotation.Scheduled
import java.math.BigDecimal
import java.time.Instant
import java.time.Clock
import java.time.ZoneOffset

class PremiumAggregationSchedulerTest {

    private lateinit var premiumCacheService: PremiumCacheService
    private lateinit var aggregationRepository: PremiumAggregationRepository
    private lateinit var jobExecutor: JobExecutor
    private lateinit var scheduler: PremiumAggregationScheduler
    private val clock = Clock.fixed(Instant.parse("2026-05-12T12:34:56Z"), ZoneOffset.UTC)
    private val windowPolicy = AggregationWindowPolicy(AggregationProperties())
    private val pair = MarketPair.default(Symbol("BTC"))

    @BeforeEach
    fun setUp() {
        premiumCacheService = mockk(relaxed = true)
        aggregationRepository = mockk(relaxed = true)
        jobExecutor = mockk()

        scheduler = PremiumAggregationScheduler(
            premiumCacheService = premiumCacheService,
            aggregationRepository = aggregationRepository,
            jobExecutor = jobExecutor,
            clock = clock,
            windowPolicy = windowPolicy,
        )
    }

    private fun aggregation() = PremiumAggregation(
        symbol = "btc",
        high = BigDecimal("2.0"),
        low = BigDecimal("1.0"),
        open = BigDecimal("1.1"),
        close = BigDecimal("1.9"),
        avg = BigDecimal("1.5"),
        count = 10,
    )

    private fun summary(current: String = "1.23") = PremiumCacheService.PremiumSummary(
        high = BigDecimal("2.0"),
        low = BigDecimal("1.0"),
        current = BigDecimal(current),
        currentTimestamp = Instant.now(),
        updatedAt = Instant.now(),
    )

    @Nested
    @DisplayName("aggregateMinute")
    inner class AggregateMinute {

        @Test
        fun `호출 시 jobExecutor execute를 호출한다`() {
            // given
            every { jobExecutor.execute(any(), any()) } returns JobResult.Success

            // when
            scheduler.aggregateMinute()

            // then
            verify(exactly = 1) { jobExecutor.execute(any(), any()) }
        }

        @Test
        fun `minute action 실행 시 분 집계 파이프라인을 수행한다`() {
            // given
            val agg = aggregation()
            every { premiumCacheService.aggregateSecondsData("btc", any(), any()) } returns agg
            every { jobExecutor.execute(any(), any()) } answers {
                val action = secondArg<() -> JobResult>()
                action()
            }

            // when
            scheduler.aggregateMinute()

            // then
            verify(exactly = 1) { premiumCacheService.aggregateSecondsData("btc", any(), any()) }
            verify(exactly = 1) { premiumCacheService.saveAggregation(AggregationTimeUnit.MINUTES, pair, any(), agg) }
            val expectedFrom = windowPolicy.previous(clock.instant(), java.time.temporal.ChronoUnit.MINUTES).from
            verify(exactly = 1) { aggregationRepository.saveMinute(pair, expectedFrom, agg) }
        }

        @Test
        fun `minute DB 저장 실패 시 cache를 기록하지 않는다`() {
            val agg = aggregation()
            every { premiumCacheService.aggregateSecondsData("btc", any(), any()) } returns agg
            every { aggregationRepository.saveMinute(any(), any(), any()) } throws RuntimeException("db failure")
            every { jobExecutor.execute(any(), any()) } answers { secondArg<() -> JobResult>().invoke() }

            scheduler.aggregateMinute()

            verify(exactly = 0) { premiumCacheService.saveAggregation(AggregationTimeUnit.MINUTES, pair, any(), any()) }
        }
    }

    @Nested
    @DisplayName("aggregateHour")
    inner class AggregateHour {

        @Test
        fun `호출 시 jobExecutor execute를 호출한다`() {
            // given
            every { jobExecutor.execute(any(), any()) } returns JobResult.Success

            // when
            scheduler.aggregateHour()

            // then
            verify(exactly = 1) { jobExecutor.execute(any(), any()) }
        }

        @Test
        fun `hour action 실행 시 시 집계 파이프라인을 수행한다`() {
            // given
            val agg = aggregation()
            every { premiumCacheService.aggregateData(AggregationTimeUnit.MINUTES, "btc", any(), any()) } returns agg
            every { jobExecutor.execute(any(), any()) } answers {
                val action = secondArg<() -> JobResult>()
                action()
            }

            // when
            scheduler.aggregateHour()

            // then
            verify(exactly = 1) { premiumCacheService.aggregateData(AggregationTimeUnit.MINUTES, "btc", any(), any()) }
            verify(exactly = 1) { premiumCacheService.saveAggregation(AggregationTimeUnit.HOURS, pair, any(), agg) }
            val expectedFrom = windowPolicy.previous(clock.instant(), java.time.temporal.ChronoUnit.HOURS).from
            verify(exactly = 1) { aggregationRepository.saveHour(pair, expectedFrom, agg) }
        }

        @Test
        fun `hour DB 저장 실패 시 cache를 기록하지 않는다`() {
            val agg = aggregation()
            every { premiumCacheService.aggregateData(AggregationTimeUnit.MINUTES, "btc", any(), any()) } returns agg
            every { aggregationRepository.saveHour(any(), any(), any()) } throws RuntimeException("db failure")
            every { jobExecutor.execute(any(), any()) } answers { secondArg<() -> JobResult>().invoke() }

            scheduler.aggregateHour()

            verify(exactly = 0) { premiumCacheService.saveAggregation(AggregationTimeUnit.HOURS, pair, any(), any()) }
        }
    }

    @Nested
    @DisplayName("aggregateDay")
    inner class AggregateDay {

        @Test
        fun `호출 시 jobExecutor execute를 호출한다`() {
            // given
            every { jobExecutor.execute(any(), any()) } returns JobResult.Success

            // when
            scheduler.aggregateDay()

            // then
            verify(exactly = 1) { jobExecutor.execute(any(), any()) }
        }

        @Test
        fun `day action 실행 시 Redis와 DB 모두 저장한다`() {
            // given
            val agg = aggregation()
            every { premiumCacheService.aggregateData(AggregationTimeUnit.HOURS, "btc", any(), any()) } returns agg
            every { jobExecutor.execute(any(), any()) } answers {
                val action = secondArg<() -> JobResult>()
                action()
            }

            // when
            scheduler.aggregateDay()

            // then
            verify(exactly = 1) { premiumCacheService.aggregateData(AggregationTimeUnit.HOURS, "btc", any(), any()) }
            verify(exactly = 1) { premiumCacheService.saveAggregation(AggregationTimeUnit.DAYS, pair, any(), agg) }
            val window = windowPolicy.previous(clock.instant(), java.time.temporal.ChronoUnit.DAYS)
            verify(exactly = 1) {
                aggregationRepository.saveDay(pair, window.from.atZone(windowPolicy.zoneId).toLocalDate(), agg)
            }
        }

        @Test
        fun `DB 저장 실패 시 commit되지 않은 집계를 cache에 기록하지 않는다`() {
            val agg = aggregation()
            every { premiumCacheService.aggregateData(AggregationTimeUnit.HOURS, "btc", any(), any()) } returns agg
            every { aggregationRepository.saveDay(any(), any(), any()) } throws RuntimeException("db failure")
            every { jobExecutor.execute(any(), any()) } answers {
                secondArg<() -> JobResult>().invoke()
            }

            scheduler.aggregateDay()

            verify(exactly = 0) {
                premiumCacheService.saveAggregation(
                    AggregationTimeUnit.DAYS,
                    any<io.premiumspread.domain.market.MarketPair>(),
                    any<java.time.Instant>(),
                    any<io.premiumspread.infrastructure.common.persistence.jdbc.premium.PremiumAggregation>(),
                )
            }
        }

        @Test
        fun `핵심 계약 스모크로 day jobName aggregation day를 전달한다`() {
            // given
            val configSlot = slot<JobConfig>()
            every { jobExecutor.execute(capture(configSlot), any()) } returns JobResult.Success

            // when
            scheduler.aggregateDay()

            // then
            assertThat(configSlot.captured.jobName).isEqualTo("aggregation:day")
        }

        @Test
        fun `day schedule은 aggregation zone 설정을 명시한다`() {
            val scheduled = PremiumAggregationScheduler::class.java
                .getDeclaredMethod("aggregateDay")
                .getAnnotation(Scheduled::class.java)

            assertThat(scheduled.zone).isEqualTo("\${aggregation.zone:Asia/Seoul}")
        }
    }

    @Nested
    @DisplayName("updateSummaryCache")
    inner class UpdateSummaryCache {

        @Test
        fun `호출 시 jobExecutor execute를 호출한다`() {
            // given
            every { jobExecutor.execute(any(), any()) } returns JobResult.Success

            // when
            scheduler.updateSummaryCache()

            // then
            verify(exactly = 1) { jobExecutor.execute(any(), any()) }
        }

        @Test
        fun `각 구간을 독립적으로 실행하며 특정 구간 실패 시에도 나머지 구간을 계속 처리한다`() {
            // given
            every { jobExecutor.execute(any(), any()) } answers {
                val action = secondArg<() -> JobResult>()
                action()
            }
            every { premiumCacheService.calculateSummaryFromSeconds(eq("btc"), any(), any()) } returnsMany listOf(
                summary("1.11"),
                summary("1.22"),
            )
            every { premiumCacheService.calculateSummary(AggregationTimeUnit.MINUTES, "btc", any(), any()) } throws RuntimeException("1h fail")
            every { premiumCacheService.calculateSummary(AggregationTimeUnit.HOURS, "btc", any(), any()) } returns summary("1.44")

            // when
            assertThatCode { scheduler.updateSummaryCache() }.doesNotThrowAnyException()

            // then
            verify(exactly = 1) { premiumCacheService.saveSummary("1m", "btc", any()) }
            verify(exactly = 1) { premiumCacheService.saveSummary("10m", "btc", any()) }
            verify(exactly = 1) { premiumCacheService.saveSummary("1d", "btc", any()) }
        }

        @Test
        fun `구간별 계산 결과가 있을 때만 저장하고 실패한 구간은 건너뛴다`() {
            // given
            every { jobExecutor.execute(any(), any()) } answers {
                val action = secondArg<() -> JobResult>()
                action()
            }
            every { premiumCacheService.calculateSummaryFromSeconds(eq("btc"), any(), any()) } returnsMany listOf(
                null,
                summary("1.22"),
            )
            every { premiumCacheService.calculateSummary(AggregationTimeUnit.MINUTES, "btc", any(), any()) } throws RuntimeException("1h fail")
            every { premiumCacheService.calculateSummary(AggregationTimeUnit.HOURS, "btc", any(), any()) } returns summary("1.44")

            // when
            assertThatCode { scheduler.updateSummaryCache() }.doesNotThrowAnyException()

            // then
            verify(exactly = 0) { premiumCacheService.saveSummary("1m", "btc", any()) }
            verify(exactly = 1) { premiumCacheService.saveSummary("10m", "btc", any()) }
            verify(exactly = 0) { premiumCacheService.saveSummary("1h", "btc", any()) }
            verify(exactly = 1) { premiumCacheService.saveSummary("1d", "btc", any()) }
        }

        @Test
        fun `핵심 계약 스모크로 summary jobName aggregation summary를 전달한다`() {
            // given
            val configSlot = slot<JobConfig>()
            every { jobExecutor.execute(capture(configSlot), any()) } returns JobResult.Success

            // when
            scheduler.updateSummaryCache()

            // then
            assertThat(configSlot.captured.jobName).isEqualTo("aggregation:summary")
        }
    }
}
