package io.premiumspread.scheduler

import io.mockk.*
import io.premiumspread.application.common.JobConfig
import io.premiumspread.application.common.JobExecutor
import io.premiumspread.application.common.JobResult
import io.premiumspread.application.job.fx.FxIngestionJob
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ExchangeRateSchedulerTest {

    private lateinit var fxIngestionJob: FxIngestionJob
    private lateinit var jobExecutor: JobExecutor
    private lateinit var scheduler: ExchangeRateScheduler

    @BeforeEach
    fun setUp() {
        fxIngestionJob = mockk()
        jobExecutor = mockk()
        scheduler = ExchangeRateScheduler(
            fxIngestionJob = fxIngestionJob,
            jobExecutor = jobExecutor,
        )
    }

    @Nested
    @DisplayName("fetchExchangeRate")
    inner class FetchExchangeRate {

        @Test
        fun `호출 시 jobExecutor execute를 1회 호출한다`() {
            // given
            every { jobExecutor.execute(any(), any()) } returns JobResult.Success

            // when
            scheduler.fetchExchangeRate()

            // then
            verify(exactly = 1) { jobExecutor.execute(any(), any()) }
        }

        @Test
        fun `execute action 실행 시 fxIngestionJob run을 호출한다`() {
            // given
            every { fxIngestionJob.run() } returns JobResult.Success
            every { jobExecutor.execute(any(), any()) } answers {
                val action = secondArg<() -> JobResult>()
                action()
            }

            // when
            scheduler.fetchExchangeRate()

            // then
            verify(exactly = 1) { fxIngestionJob.run() }
        }

        @Test
        fun `핵심 계약 스모크로 jobName fx를 전달한다`() {
            // given
            val configSlot = slot<JobConfig>()
            every { jobExecutor.execute(capture(configSlot), any()) } returns JobResult.Success

            // when
            scheduler.fetchExchangeRate()

            // then
            assertThat(configSlot.captured.jobName).isEqualTo("fx")
        }
    }

    @Nested
    @DisplayName("fetchExchangeRateOnStartup")
    inner class FetchExchangeRateOnStartup {

        @Test
        fun `호출 시 fetchExchangeRate 경로를 통해 execute를 호출한다`() {
            // given
            every { jobExecutor.execute(any(), any()) } returns JobResult.Success

            // when
            scheduler.fetchExchangeRateOnStartup()

            // then
            verify(exactly = 1) { jobExecutor.execute(any(), any()) }
        }
    }
}
