package io.premiumspread.infrastructure.batch.job

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.premiumspread.domain.job.JobFailureStreak
import io.premiumspread.domain.job.JobId
import io.premiumspread.domain.job.JobRunOutcome
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.Executors

class RedisJobRunRecorderFailureStreakTest {
    private val redis = mockk<StringRedisTemplate>()
    private val values = mockk<ValueOperations<String, String>>()
    private val t0 = Instant.parse("2026-10-28T00:00:00Z")
    private val t1 = t0.plusSeconds(30)
    private val t2 = t0.plusSeconds(60)
    private val recorder: RedisJobRunRecorderAdapter

    init {
        every { redis.opsForValue() } returns values
        every { values.set(any(), any(), any<Duration>()) } just runs
        recorder = RedisJobRunRecorderAdapter(redis, SimpleMeterRegistry(), Clock.fixed(t0, ZoneOffset.UTC))
    }

    private fun complete(jobId: JobId, outcome: JobRunOutcome, at: Instant) =
        recorder.completed(jobId, "run-$at-${outcome.name}", outcome, at, null)

    @Test
    fun `failed three times accumulates count with first and last failure`() {
        complete(JobId.PREMIUM_REALTIME, JobRunOutcome.FAILED, t0)
        complete(JobId.PREMIUM_REALTIME, JobRunOutcome.FAILED, t1)
        complete(JobId.PREMIUM_REALTIME, JobRunOutcome.FAILED, t2)

        assertThat(recorder.findFailureStreak(JobId.PREMIUM_REALTIME))
            .isEqualTo(JobFailureStreak(JobId.PREMIUM_REALTIME, 3, t0, t2))
    }

    @Test
    fun `skipped leaves streak unchanged`() {
        complete(JobId.PREMIUM_REALTIME, JobRunOutcome.FAILED, t0)
        complete(JobId.PREMIUM_REALTIME, JobRunOutcome.SKIPPED, t1)

        assertThat(recorder.findFailureStreak(JobId.PREMIUM_REALTIME))
            .isEqualTo(JobFailureStreak(JobId.PREMIUM_REALTIME, 1, t0, t0))
    }

    @Test
    fun `skipped without prior failure keeps streak empty`() {
        complete(JobId.PREMIUM_REALTIME, JobRunOutcome.SKIPPED, t0)

        assertThat(recorder.findFailureStreak(JobId.PREMIUM_REALTIME)).isNull()
    }

    @Test
    fun `succeeded clears streak and next failure restarts from one`() {
        complete(JobId.PREMIUM_REALTIME, JobRunOutcome.FAILED, t0)
        complete(JobId.PREMIUM_REALTIME, JobRunOutcome.FAILED, t1)
        complete(JobId.PREMIUM_REALTIME, JobRunOutcome.SUCCEEDED, t2)
        assertThat(recorder.findFailureStreak(JobId.PREMIUM_REALTIME)).isNull()

        val later = t2.plusSeconds(30)
        complete(JobId.PREMIUM_REALTIME, JobRunOutcome.FAILED, later)

        assertThat(recorder.findFailureStreak(JobId.PREMIUM_REALTIME))
            .isEqualTo(JobFailureStreak(JobId.PREMIUM_REALTIME, 1, later, later))
    }

    @Test
    fun `streaks are independent per job`() {
        complete(JobId.PREMIUM_REALTIME, JobRunOutcome.FAILED, t0)
        complete(JobId.PREMIUM_REALTIME, JobRunOutcome.FAILED, t1)
        complete(JobId.FX_INGESTION, JobRunOutcome.FAILED, t2)
        complete(JobId.FX_INGESTION, JobRunOutcome.SUCCEEDED, t2)

        assertThat(recorder.findFailureStreak(JobId.PREMIUM_REALTIME)?.consecutiveFailures).isEqualTo(2)
        assertThat(recorder.findFailureStreak(JobId.FX_INGESTION)).isNull()
    }

    @Test
    fun `streak is updated even when redis write throws afterwards`() {
        complete(JobId.PREMIUM_REALTIME, JobRunOutcome.FAILED, t0)
        every { values.set(any(), any(), any<Duration>()) } throws RuntimeException("redis down")

        assertThatThrownBy { complete(JobId.PREMIUM_REALTIME, JobRunOutcome.SUCCEEDED, t1) }
            .isInstanceOf(RuntimeException::class.java)

        assertThat(recorder.findFailureStreak(JobId.PREMIUM_REALTIME)).isNull()
    }

    @Test
    fun `concurrent failures are all counted`() {
        Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            val futures = (1..100).map { i ->
                executor.submit {
                    recorder.completed(JobId.PREMIUM_REALTIME, "run-$i", JobRunOutcome.FAILED, t0.plusSeconds(i.toLong()), null)
                }
            }
            futures.forEach { it.get() }
        }

        assertThat(recorder.findFailureStreak(JobId.PREMIUM_REALTIME)?.consecutiveFailures).isEqualTo(100)
    }
}
