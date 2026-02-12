package io.premiumspread.scheduler

import io.mockk.*
import io.premiumspread.application.common.JobConfig
import io.premiumspread.application.common.JobExecutor
import io.premiumspread.application.common.JobResult
import io.premiumspread.cache.TickerCacheService
import io.premiumspread.redis.TickerAggregationTimeUnit
import io.premiumspread.repository.TickerAggregation
import io.premiumspread.repository.TickerAggregationRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class TickerAggregationSchedulerTest {

    private lateinit var tickerCacheService: TickerCacheService
    private lateinit var aggregationRepository: TickerAggregationRepository
    private lateinit var jobExecutor: JobExecutor
    private lateinit var scheduler: TickerAggregationScheduler

    @BeforeEach
    fun setUp() {
        tickerCacheService = mockk(relaxed = true)
        aggregationRepository = mockk(relaxed = true)
        jobExecutor = mockk()

        scheduler = TickerAggregationScheduler(
            tickerCacheService = tickerCacheService,
            aggregationRepository = aggregationRepository,
            jobExecutor = jobExecutor,
        )
    }

    private fun tickerAgg(exchange: String, symbol: String) = TickerAggregation(
        exchange = exchange,
        symbol = symbol,
        high = BigDecimal("110"),
        low = BigDecimal("90"),
        open = BigDecimal("100"),
        close = BigDecimal("105"),
        avg = BigDecimal("102"),
        count = 10,
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
        fun `minute action 실행 시 TARGETS에 대해 분 집계 파이프라인을 수행한다`() {
            // given
            val bithumbAgg = tickerAgg("bithumb", "btc")
            val binanceAgg = tickerAgg("binance", "btc")
            every { tickerCacheService.aggregateSecondsData("bithumb", "btc", any(), any()) } returns bithumbAgg
            every { tickerCacheService.aggregateSecondsData("binance", "btc", any(), any()) } returns binanceAgg
            every { jobExecutor.execute(any(), any()) } answers {
                val action = secondArg<() -> JobResult>()
                action()
            }

            // when
            scheduler.aggregateMinute()

            // then
            verify(exactly = 1) { tickerCacheService.aggregateSecondsData("bithumb", "btc", any(), any()) }
            verify(exactly = 1) { tickerCacheService.aggregateSecondsData("binance", "btc", any(), any()) }
            verify(exactly = 1) { tickerCacheService.saveAggregation(TickerAggregationTimeUnit.MINUTES, "bithumb", "btc", any(), bithumbAgg) }
            verify(exactly = 1) { tickerCacheService.saveAggregation(TickerAggregationTimeUnit.MINUTES, "binance", "btc", any(), binanceAgg) }
            verify(exactly = 1) { aggregationRepository.saveMinute("bithumb", "btc", any(), bithumbAgg) }
            verify(exactly = 1) { aggregationRepository.saveMinute("binance", "btc", any(), binanceAgg) }
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
        fun `hour action 실행 시 TARGETS에 대해 시 집계 파이프라인을 수행한다`() {
            // given
            val bithumbAgg = tickerAgg("bithumb", "btc")
            val binanceAgg = tickerAgg("binance", "btc")
            every { tickerCacheService.aggregateData(TickerAggregationTimeUnit.MINUTES, "bithumb", "btc", any(), any()) } returns bithumbAgg
            every { tickerCacheService.aggregateData(TickerAggregationTimeUnit.MINUTES, "binance", "btc", any(), any()) } returns binanceAgg
            every { jobExecutor.execute(any(), any()) } answers {
                val action = secondArg<() -> JobResult>()
                action()
            }

            // when
            scheduler.aggregateHour()

            // then
            verify(exactly = 1) { tickerCacheService.aggregateData(TickerAggregationTimeUnit.MINUTES, "bithumb", "btc", any(), any()) }
            verify(exactly = 1) { tickerCacheService.aggregateData(TickerAggregationTimeUnit.MINUTES, "binance", "btc", any(), any()) }
            verify(exactly = 1) { tickerCacheService.saveAggregation(TickerAggregationTimeUnit.HOURS, "bithumb", "btc", any(), bithumbAgg) }
            verify(exactly = 1) { tickerCacheService.saveAggregation(TickerAggregationTimeUnit.HOURS, "binance", "btc", any(), binanceAgg) }
            verify(exactly = 1) { aggregationRepository.saveHour("bithumb", "btc", any(), bithumbAgg) }
            verify(exactly = 1) { aggregationRepository.saveHour("binance", "btc", any(), binanceAgg) }
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
        fun `day action 실행 시 TARGETS에 대해 일 집계 DB 적재를 수행한다`() {
            // given
            val bithumbAgg = tickerAgg("bithumb", "btc")
            val binanceAgg = tickerAgg("binance", "btc")
            every { tickerCacheService.aggregateData(TickerAggregationTimeUnit.HOURS, "bithumb", "btc", any(), any()) } returns bithumbAgg
            every { tickerCacheService.aggregateData(TickerAggregationTimeUnit.HOURS, "binance", "btc", any(), any()) } returns binanceAgg
            every { jobExecutor.execute(any(), any()) } answers {
                val action = secondArg<() -> JobResult>()
                action()
            }

            // when
            scheduler.aggregateDay()

            // then
            verify(exactly = 1) { tickerCacheService.aggregateData(TickerAggregationTimeUnit.HOURS, "bithumb", "btc", any(), any()) }
            verify(exactly = 1) { tickerCacheService.aggregateData(TickerAggregationTimeUnit.HOURS, "binance", "btc", any(), any()) }
            verify(exactly = 1) { aggregationRepository.saveDay("bithumb", "btc", any(), bithumbAgg) }
            verify(exactly = 1) { aggregationRepository.saveDay("binance", "btc", any(), binanceAgg) }
        }

        @Test
        fun `핵심 계약 스모크로 day jobName ticker aggregation day를 전달한다`() {
            // given
            val configSlot = slot<JobConfig>()
            every { jobExecutor.execute(capture(configSlot), any()) } returns JobResult.Success

            // when
            scheduler.aggregateDay()

            // then
            assertThat(configSlot.captured.jobName).isEqualTo("ticker:aggregation:day")
        }
    }

    @Nested
    @DisplayName("runForAllTargets 결과 규칙")
    inner class RunForAllTargetsRule {

        @Test
        fun `하나라도 성공이면 최종 결과는 Success다`() {
            // given
            val actionSlot = slot<() -> JobResult>()
            every { tickerCacheService.aggregateSecondsData("bithumb", "btc", any(), any()) } returns tickerAgg("bithumb", "btc")
            every { tickerCacheService.aggregateSecondsData("binance", "btc", any(), any()) } returns null
            every { jobExecutor.execute(any(), capture(actionSlot)) } returns JobResult.Success

            // when
            scheduler.aggregateMinute()
            val result = actionSlot.captured.invoke()

            // then
            assertThat(result).isEqualTo(JobResult.Success)
        }

        @Test
        fun `전부 no data면 최종 결과는 Skipped no_data다`() {
            // given
            val actionSlot = slot<() -> JobResult>()
            every { tickerCacheService.aggregateSecondsData("bithumb", "btc", any(), any()) } returns null
            every { tickerCacheService.aggregateSecondsData("binance", "btc", any(), any()) } returns null
            every { jobExecutor.execute(any(), capture(actionSlot)) } returns JobResult.Success

            // when
            scheduler.aggregateMinute()
            val result = actionSlot.captured.invoke()

            // then
            assertThat(result).isInstanceOf(JobResult.Skipped::class.java)
            assertThat((result as JobResult.Skipped).reason).isEqualTo("no_data")
        }

        @Test
        fun `중간 Failure 발생 시 즉시 Failure를 반환한다`() {
            // given
            val actionSlot = slot<() -> JobResult>()
            every { tickerCacheService.aggregateSecondsData("bithumb", "btc", any(), any()) } throws RuntimeException("boom")
            every { tickerCacheService.aggregateSecondsData("binance", "btc", any(), any()) } returns tickerAgg("binance", "btc")
            every { jobExecutor.execute(any(), capture(actionSlot)) } returns JobResult.Success

            // when
            scheduler.aggregateMinute()
            val result = actionSlot.captured.invoke()

            // then
            assertThat(result).isInstanceOf(JobResult.Failure::class.java)
            assertThat((result as JobResult.Failure).exception.message).isEqualTo("boom")
            verify(exactly = 0) { tickerCacheService.aggregateSecondsData("binance", "btc", any(), any()) }
        }
    }
}
