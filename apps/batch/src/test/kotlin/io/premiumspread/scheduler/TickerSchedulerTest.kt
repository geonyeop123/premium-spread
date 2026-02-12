package io.premiumspread.scheduler

import io.mockk.*
import io.premiumspread.application.common.JobConfig
import io.premiumspread.application.common.JobExecutor
import io.premiumspread.application.common.JobResult
import io.premiumspread.application.job.ticker.TickerIngestionJob
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class TickerSchedulerTest {

    private lateinit var tickerIngestionJob: TickerIngestionJob
    private lateinit var jobExecutor: JobExecutor
    private lateinit var scheduler: TickerScheduler

    @BeforeEach
    fun setUp() {
        tickerIngestionJob = mockk()
        jobExecutor = mockk()
        scheduler = TickerScheduler(
            tickerIngestionJob = tickerIngestionJob,
            jobExecutor = jobExecutor,
        )
    }

    @Nested
    @DisplayName("fetchTickers")
    inner class FetchTickers {

        @Test
        fun `호출 시 jobExecutor execute를 1회 호출한다`() {
            // given
            every { jobExecutor.execute(any(), any()) } returns JobResult.Success

            // when
            scheduler.fetchTickers()

            // then
            verify(exactly = 1) { jobExecutor.execute(any(), any()) }
        }

        @Test
        fun `execute action 실행 시 tickerIngestionJob run을 호출한다`() {
            // given
            every { tickerIngestionJob.run() } returns JobResult.Success
            every { jobExecutor.execute(any(), any()) } answers {
                val action = secondArg<() -> JobResult>()
                action()
            }

            // when
            scheduler.fetchTickers()

            // then
            verify(exactly = 1) { tickerIngestionJob.run() }
        }

        @Test
        fun `핵심 계약 스모크로 jobName ticker를 전달한다`() {
            // given
            val configSlot = slot<JobConfig>()
            every { jobExecutor.execute(capture(configSlot), any()) } returns JobResult.Success

            // when
            scheduler.fetchTickers()

            // then
            assertThat(configSlot.captured.jobName).isEqualTo("ticker")
        }
    }
}
