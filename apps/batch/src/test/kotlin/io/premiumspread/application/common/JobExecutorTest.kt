package io.premiumspread.application.common

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.*
import io.premiumspread.redis.DistributedLockManager
import io.premiumspread.redis.DistributedLockManager.LockResult
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import java.time.Duration
import java.util.concurrent.TimeUnit

class JobExecutorTest {

    private lateinit var lockManager: DistributedLockManager
    private lateinit var redisTemplate: StringRedisTemplate
    private lateinit var meterRegistry: MeterRegistry
    private lateinit var valueOps: ValueOperations<String, String>
    private lateinit var jobExecutor: JobExecutor

    @BeforeEach
    fun setUp() {
        lockManager = mockk()
        redisTemplate = mockk()
        valueOps = mockk(relaxed = true)
        every { redisTemplate.opsForValue() } returns valueOps
        meterRegistry = SimpleMeterRegistry()
        jobExecutor = JobExecutor(lockManager, redisTemplate, meterRegistry)
    }

    private fun jobConfig(
        jobName: String = "test-job",
        lockKey: String = "lock:test",
        leaseTime: Long = 2,
    ) = JobConfig(
        jobName = jobName,
        lockKey = lockKey,
        leaseTime = leaseTime,
        leaseTimeUnit = TimeUnit.SECONDS,
    )

    @Nested
    @DisplayName("실행")
    inner class Execute {

        @Test
        fun `락 획득 후 job 성공 시 success 메트릭을 기록한다`() {
            // given
            val config = jobConfig()
            every {
                lockManager.withLock(
                    lockKey = "lock:test",
                    waitTime = 0,
                    leaseTime = 2,
                    timeUnit = TimeUnit.SECONDS,
                    action = any<() -> JobResult>(),
                )
            } answers {
                val action = arg<() -> JobResult>(4)
                LockResult.Success(action())
            }

            // when
            val result = jobExecutor.execute(config) { JobResult.Success }

            // then
            assertThat(result).isEqualTo(JobResult.Success)
            assertThat(meterRegistry.counter("scheduler.test-job.success").count()).isEqualTo(1.0)
            verify { valueOps.set(match { it.contains("test-job") }, any(), any<Duration>()) }
        }

        @Test
        fun `job이 Skipped를 반환하면 skipped 메트릭을 기록한다`() {
            // given
            val config = jobConfig()
            every {
                lockManager.withLock(
                    lockKey = any(),
                    waitTime = any(),
                    leaseTime = any(),
                    timeUnit = any(),
                    action = any<() -> JobResult>(),
                )
            } answers {
                val action = arg<() -> JobResult>(4)
                LockResult.Success(action())
            }

            // when
            val result = jobExecutor.execute(config) { JobResult.Skipped("missing_data") }

            // then
            assertThat(result).isInstanceOf(JobResult.Skipped::class.java)
            assertThat(meterRegistry.counter("scheduler.test-job.skipped", "reason", "missing_data").count()).isEqualTo(1.0)
        }

        @Test
        fun `job이 Failure를 반환하면 error 메트릭을 기록한다`() {
            // given
            val config = jobConfig()
            val exception = RuntimeException("db error")
            every {
                lockManager.withLock(
                    lockKey = any(),
                    waitTime = any(),
                    leaseTime = any(),
                    timeUnit = any(),
                    action = any<() -> JobResult>(),
                )
            } answers {
                val action = arg<() -> JobResult>(4)
                LockResult.Success(action())
            }

            // when
            val result = jobExecutor.execute(config) { JobResult.Failure(exception) }

            // then
            assertThat(result).isInstanceOf(JobResult.Failure::class.java)
            assertThat(meterRegistry.counter("scheduler.test-job.error", "error", "RuntimeException").count()).isEqualTo(1.0)
        }

        @Test
        fun `락 미획득 시 reason이 lock인 skipped 메트릭을 기록한다`() {
            // given
            val config = jobConfig()
            every {
                lockManager.withLock(
                    lockKey = any(),
                    waitTime = any(),
                    leaseTime = any(),
                    timeUnit = any(),
                    action = any<() -> JobResult>(),
                )
            } returns LockResult.NotAcquired()

            // when
            val result = jobExecutor.execute(config) { JobResult.Success }

            // then
            assertThat(result).isInstanceOf(JobResult.Skipped::class.java)
            assertThat((result as JobResult.Skipped).reason).isEqualTo("lock")
            assertThat(meterRegistry.counter("scheduler.test-job.skipped", "reason", "lock").count()).isEqualTo(1.0)
        }

        @Test
        fun `락이 Error를 반환하면 error 메트릭을 기록한다`() {
            // given
            val config = jobConfig()
            val exception = RuntimeException("redis down")
            every {
                lockManager.withLock(
                    lockKey = any(),
                    waitTime = any(),
                    leaseTime = any(),
                    timeUnit = any(),
                    action = any<() -> JobResult>(),
                )
            } returns LockResult.Error(exception)

            // when
            val result = jobExecutor.execute(config) { JobResult.Success }

            // then
            assertThat(result).isInstanceOf(JobResult.Failure::class.java)
            assertThat(meterRegistry.counter("scheduler.test-job.error", "error", "RuntimeException").count()).isEqualTo(1.0)
        }

        @Test
        fun `job이 skip되면 last-run 시간을 갱신하지 않는다`() {
            // given
            val config = jobConfig()
            every {
                lockManager.withLock(
                    lockKey = any(),
                    waitTime = any(),
                    leaseTime = any(),
                    timeUnit = any(),
                    action = any<() -> JobResult>(),
                )
            } returns LockResult.NotAcquired()

            // when
            jobExecutor.execute(config) { JobResult.Success }

            // then
            verify(exactly = 0) { valueOps.set(any(), any(), any<Duration>()) }
        }
    }
}
