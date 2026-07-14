package io.premiumspread.application.common

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import io.premiumspread.domain.job.JobId
import io.premiumspread.domain.job.JobLock
import io.premiumspread.domain.job.JobRunOutcome
import io.premiumspread.domain.job.JobRunRecorder
import io.premiumspread.domain.job.OperatorAlert
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class JobExecutorTest {
    private val lock = mockk<JobLock>()
    private val recorder = mockk<JobRunRecorder>(relaxed = true)
    private val alert = mockk<OperatorAlert>(relaxed = true)
    private val clock = Clock.fixed(Instant.parse("2026-07-14T00:00:00Z"), ZoneOffset.UTC)
    private lateinit var executor: JobExecutor
    private val config = JobConfig(
        JobId.FX_INGESTION,
        "lock:fx",
        Duration.ofSeconds(30),
        Duration.ofSeconds(20),
    )

    @BeforeEach
    fun setUp() {
        every { lock.renew(any(), any(), any()) } returns true
        executor = JobExecutor(lock, recorder, alert, clock)
    }

    @Test
    fun `성공 작업은 bounded id로 기록하고 lock을 해제한다`() {
        every { lock.tryAcquire(any(), any(), any(), any()) } returns true
        every { lock.release(any(), any()) } returns Unit

        val result = executor.execute(config) { JobResult.Success }

        assertThat(result).isEqualTo(JobResult.Success)
        verify { recorder.started(JobId.FX_INGESTION, any(), clock.instant()) }
        verify { recorder.completed(JobId.FX_INGESTION, any(), JobRunOutcome.SUCCEEDED, clock.instant(), null) }
        verify { lock.release("lock:fx", any()) }
        verify(exactly = 0) { alert.send(any()) }
    }

    @Test
    fun `실패 알림은 lock release 이후에 전달한다`() {
        every { lock.tryAcquire(any(), any(), any(), any()) } returns true
        every { lock.release(any(), any()) } returns Unit

        val result = executor.execute(config) { JobResult.Failure(IllegalStateException("boom")) }

        assertThat(result).isInstanceOf(JobResult.Failure::class.java)
        verifyOrder {
            lock.release("lock:fx", any())
            alert.send(match { it.attributes["job"] == JobId.FX_INGESTION.tag })
        }
    }

    @Test
    fun `lock 미획득은 action을 실행하지 않고 skipped로 기록한다`() {
        every { lock.tryAcquire(any(), any(), any(), any()) } returns false
        var invoked = false

        val result = executor.execute(config) {
            invoked = true
            JobResult.Success
        }

        assertThat(invoked).isFalse()
        assertThat((result as JobResult.Skipped).reason).isEqualTo("lock")
        verify { recorder.completed(JobId.FX_INGESTION, any(), JobRunOutcome.SKIPPED, clock.instant(), "lock") }
        verify(exactly = 0) { lock.release(any(), any()) }
    }

    @Test
    fun `lock 획득 예외는 failure로 기록하고 알림을 전달한다`() {
        every { lock.tryAcquire(any(), any(), any(), any()) } throws IllegalStateException("redis down")

        val result = executor.execute(config) { JobResult.Success }

        assertThat(result).isInstanceOf(JobResult.Failure::class.java)
        verify { recorder.completed(JobId.FX_INGESTION, any(), JobRunOutcome.FAILED, clock.instant(), "IllegalStateException") }
        verify { alert.send(any()) }
    }

    @Test
    fun `execution timeout을 넘긴 interruptible 작업은 중단하고 실패로 기록한다`() {
        every { lock.tryAcquire(any(), any(), any(), any()) } returns true
        every { lock.release(any(), any()) } returns Unit
        val shortConfig = config.copy(
            lease = Duration.ofMillis(200),
            executionTimeout = Duration.ofMillis(50),
        )

        val result = executor.execute(shortConfig) {
            Thread.sleep(5_000)
            JobResult.Success
        }

        assertThat((result as JobResult.Failure).exception).isInstanceOf(JobExecutionTimeoutException::class.java)
        verify(timeout = 1_000) { lock.release("lock:fx", any()) }
        verify(timeout = 1_000) {
            recorder.completed(JobId.FX_INGESTION, any(), JobRunOutcome.FAILED, clock.instant(), "JobExecutionTimeoutException")
        }
        verify(timeout = 1_000) { alert.send(any()) }
    }

    @Test
    fun `timeout interrupt를 무시하는 작업은 실제 종료까지 lease를 갱신하고 lock을 유지한다`() {
        every { lock.tryAcquire(any(), any(), any(), any()) } returns true
        every { lock.release(any(), any()) } returns Unit
        val shortConfig = config.copy(
            lease = Duration.ofMillis(90),
            executionTimeout = Duration.ofMillis(20),
        )
        val mayFinish = CountDownLatch(1)
        val started = CountDownLatch(1)
        val running = AtomicBoolean(true)

        val result = executor.execute(shortConfig) {
            started.countDown()
            while (running.get()) {
                try {
                    mayFinish.await(10, TimeUnit.MILLISECONDS)
                    if (mayFinish.count == 0L) running.set(false)
                } catch (_: InterruptedException) {
                    // 취소를 무시하는 외부 I/O를 재현한다.
                }
            }
            JobResult.Success
        }

        assertThat(started.count).isZero()
        assertThat((result as JobResult.Failure).exception).isInstanceOf(JobExecutionTimeoutException::class.java)
        verify(exactly = 0) { lock.release("lock:fx", any()) }
        verify {
            recorder.completed(JobId.FX_INGESTION, any(), JobRunOutcome.FAILED, clock.instant(), "JobExecutionTimeoutException")
        }
        verify { alert.send(match { it.attributes["lock_retained"] == "true" }) }
        verify(timeout = 500, atLeast = 1) { lock.renew("lock:fx", any(), Duration.ofMillis(90)) }

        mayFinish.countDown()

        verify(timeout = 1_000) { lock.release("lock:fx", any()) }
        verify(exactly = 1) {
            recorder.completed(JobId.FX_INGESTION, any(), JobRunOutcome.FAILED, clock.instant(), "JobExecutionTimeoutException")
        }
    }
}
