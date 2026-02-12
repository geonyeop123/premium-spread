package io.premiumspread.scheduler

import io.mockk.*
import io.premiumspread.application.common.JobConfig
import io.premiumspread.application.common.JobExecutor
import io.premiumspread.application.common.JobResult
import io.premiumspread.application.job.premium.PremiumRealtimeJob
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class PremiumSchedulerTest {

    private lateinit var premiumRealtimeJob: PremiumRealtimeJob
    private lateinit var jobExecutor: JobExecutor
    private lateinit var scheduler: PremiumScheduler

    @BeforeEach
    fun setUp() {
        premiumRealtimeJob = mockk()
        jobExecutor = mockk()
        scheduler = PremiumScheduler(
            premiumRealtimeJob = premiumRealtimeJob,
            jobExecutor = jobExecutor,
        )
    }

    @Nested
    @DisplayName("calculatePremium")
    inner class CalculatePremium {

        @Test
        fun `호출 시 jobExecutor execute를 1회 호출한다`() {
            // given
            every { jobExecutor.execute(any(), any()) } returns JobResult.Success

            // when
            scheduler.calculatePremium()

            // then
            verify(exactly = 1) { jobExecutor.execute(any(), any()) }
        }

        @Test
        fun `execute action 실행 시 premiumRealtimeJob run을 호출한다`() {
            // given
            every { premiumRealtimeJob.run() } returns JobResult.Success
            every { jobExecutor.execute(any(), any()) } answers {
                val action = secondArg<() -> JobResult>()
                action()
            }

            // when
            scheduler.calculatePremium()

            // then
            verify(exactly = 1) { premiumRealtimeJob.run() }
        }

        @Test
        fun `핵심 계약 스모크로 jobName premium을 전달한다`() {
            // given
            val configSlot = slot<JobConfig>()
            every { jobExecutor.execute(capture(configSlot), any()) } returns JobResult.Success

            // when
            scheduler.calculatePremium()

            // then
            assertThat(configSlot.captured.jobName).isEqualTo("premium")
        }
    }
}
