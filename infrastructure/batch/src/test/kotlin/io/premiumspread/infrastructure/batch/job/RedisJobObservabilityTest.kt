package io.premiumspread.infrastructure.batch.job

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.premiumspread.domain.job.JobId
import io.premiumspread.domain.job.JobRunOutcome
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.data.redis.RedisConnectionFailureException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class RedisJobObservabilityTest {
    private val redis = mockk<StringRedisTemplate>()
    private val values = mockk<ValueOperations<String, String>>()
    private val registry = SimpleMeterRegistry()

    @Test
    fun `job exposes outcome duration last success age and bounded premium reason`() {
        every { redis.opsForValue() } returns values
        every { values.set(any(), any(), any<Duration>()) } just runs
        val completedAt = Instant.parse("2026-07-15T00:00:10Z")
        val recorder = RedisJobRunRecorderAdapter(
            redis,
            registry,
            Clock.fixed(completedAt.plusSeconds(5), ZoneOffset.UTC),
        )
        recorder.started(JobId.PREMIUM_REALTIME, "run-1", completedAt.minusSeconds(2))

        recorder.completed(JobId.PREMIUM_REALTIME, "run-1", JobRunOutcome.SUCCEEDED, completedAt, null)
        recorder.completed(JobId.PREMIUM_REALTIME, "run-2", JobRunOutcome.SKIPPED, completedAt, "invalid_price")

        assertThat(
            registry.find("batch.job.run")
                .tag("job", "premium_realtime")
                .counters()
                .sumOf { it.count() },
        ).isEqualTo(2.0)
        assertThat(registry.get("batch.job.duration").tag("job", "premium_realtime").timer().count()).isEqualTo(1)
        assertThat(registry.get("batch.job.last.success.age").tag("job", "premium_realtime").gauge().value())
            .isEqualTo(5.0)
        assertThat(registry.get("premium.calculation").tag("outcome", "invalid").counter().count()).isEqualTo(1.0)
    }

    @Test
    fun `lock not acquired and redis errors have bounded outcomes without lock key tags`() {
        every { redis.opsForValue() } returns values
        every { values.setIfAbsent(any(), any(), any<Duration>()) } returns false
        val adapter = RedisJobLockAdapter(redis, registry)

        assertThat(adapter.tryAcquire("runtime-lock-key", "owner", Duration.ofSeconds(5), Instant.EPOCH)).isFalse()
        assertThat(registry.get("batch.job.lock").tag("outcome", "not_acquired").counter().count()).isEqualTo(1.0)
        assertThat(registry.find("batch.job.lock").meters().flatMap { it.id.tags })
            .noneMatch { it.key == "key" || it.key == "owner" }

        val redisFailure = RedisConnectionFailureException("down")
        every { values.setIfAbsent(any(), any(), any<Duration>()) } throws redisFailure

        val propagated = runCatching {
            adapter.tryAcquire("another-key", "owner", Duration.ofSeconds(5), Instant.EPOCH)
        }.exceptionOrNull()

        assertThat(propagated).isSameAs(redisFailure)
        assertThat(registry.get("batch.job.lock").tag("outcome", "error").counter().count()).isEqualTo(1.0)
    }
}
