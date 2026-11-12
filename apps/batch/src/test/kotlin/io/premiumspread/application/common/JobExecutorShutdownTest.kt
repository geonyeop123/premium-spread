package io.premiumspread.application.common

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.premiumspread.domain.job.JobId
import io.premiumspread.domain.job.JobLock
import io.premiumspread.domain.job.JobRunOutcome
import io.premiumspread.domain.job.JobRunRecorder
import io.premiumspread.domain.job.OperatorAlert
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.context.SmartLifecycle
import org.springframework.context.event.ContextClosedEvent
import org.springframework.context.support.GenericApplicationContext
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Supplier

class JobExecutorShutdownTest {
    private val lock = mockk<JobLock>()
    private val recorder = mockk<JobRunRecorder>(relaxed = true)
    private val alert = mockk<OperatorAlert>(relaxed = true)
    private val clock = Clock.fixed(Instant.parse("2026-07-14T00:00:00Z"), ZoneOffset.UTC)
    private val closedEvent = ContextClosedEvent(GenericApplicationContext())
    private lateinit var executor: JobExecutor
    private val config = JobConfig(
        JobId.FX_INGESTION,
        "lock:fx",
        Duration.ofSeconds(30),
        Duration.ofSeconds(20),
    )

    @BeforeEach
    fun setUp() {
        every { lock.tryAcquire(any(), any(), any(), any()) } returns true
        every { lock.renew(any(), any(), any()) } returns true
        every { lock.release(any(), any()) } returns Unit
        executor = JobExecutor(lock, recorder, alert, clock)
    }

    @AfterEach
    fun tearDown() {
        executor.close()
    }

    @Test
    fun `종료 신호 뒤 새 실행은 lock과 action 없이 shutdown으로 건너뛴다`() {
        executor.onApplicationEvent(closedEvent)
        var invoked = false

        val result = executor.execute(config) {
            invoked = true
            JobResult.Success
        }

        assertThat(result).isEqualTo(JobResult.Skipped(JobExecutor.SHUTDOWN))
        assertThat(invoked).isFalse()
        verify(exactly = 0) { lock.tryAcquire(any(), any(), any(), any()) }
        verify { recorder.completed(JobId.FX_INGESTION, any(), JobRunOutcome.SKIPPED, clock.instant(), "shutdown") }
        verify(exactly = 0) { alert.send(any()) }
    }

    @Test
    fun `실행 중 종료 신호 뒤 action 예외는 shutdown 으로 기록하고 알리지 않는다`() {
        val result = runFailingDuringShutdown(RuntimeException("LettuceConnectionFactory is STOPPING"))

        assertThat(result).isEqualTo(JobResult.Skipped("shutdown"))
        verify { recorder.completed(JobId.FX_INGESTION, any(), JobRunOutcome.SKIPPED, clock.instant(), "shutdown") }
        verify(exactly = 0) { recorder.completed(any(), any(), JobRunOutcome.FAILED, any(), any()) }
        verify(exactly = 0) { alert.send(any()) }
        verify(exactly = 1) { lock.release("lock:fx", any()) }
    }

    @Test
    fun `실행 중 종료 신호 뒤 InterruptedException도 shutdown 으로 기록한다`() {
        val result = runFailingDuringShutdown(InterruptedException("worker interrupted"))

        assertThat(result).isEqualTo(JobResult.Skipped("shutdown"))
        verify { recorder.completed(JobId.FX_INGESTION, any(), JobRunOutcome.SKIPPED, clock.instant(), "shutdown") }
        verify(exactly = 0) { alert.send(any()) }
        verify(exactly = 1) { lock.release("lock:fx", any()) }
    }

    @Test
    fun `종료 중 timeout 은 shutdown 으로 기록한다`() {
        val shortConfig = config.copy(lease = Duration.ofMillis(500), executionTimeout = Duration.ofMillis(100))
        val mayFinish = CountDownLatch(1)

        val result = executor.execute(shortConfig) {
            // 이벤트를 action 안에서 보내 timeout 보다 반드시 먼저 일어나게 한다.
            executor.onApplicationEvent(closedEvent)
            awaitIgnoringInterrupt(mayFinish)
            JobResult.Success
        }

        // action 이 cancel interrupt 를 무시하므로 반드시 보류 해제(actionMayStillRun) 경로를 탄다.
        assertThat(result).isEqualTo(JobResult.Skipped("shutdown"))
        verify { recorder.completed(JobId.FX_INGESTION, any(), JobRunOutcome.SKIPPED, clock.instant(), "shutdown") }
        verify(exactly = 0) { lock.release(any(), any()) }

        mayFinish.countDown()

        verify(timeout = 2_000, exactly = 1) { lock.release("lock:fx", any()) }
        verify(exactly = 0) { alert.send(any()) }
    }

    @Test
    fun `종료 중 대기하던 호출 스레드 interrupt 는 shutdown 으로 끝나고 플래그를 유지한다`() {
        val started = CountDownLatch(1)
        val neverReleased = CountDownLatch(1)
        val result = AtomicReference<JobResult>()
        val interruptedAfter = AtomicBoolean(false)
        val caller = Thread {
            result.set(
                executor.execute(config) {
                    started.countDown()
                    neverReleased.await()
                    JobResult.Success
                },
            )
            interruptedAfter.set(Thread.currentThread().isInterrupted)
        }
        caller.start()
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue()

        executor.onApplicationEvent(closedEvent)
        caller.interrupt()
        caller.join(5_000)

        assertThat(caller.isAlive).isFalse()
        assertThat(result.get()).isEqualTo(JobResult.Skipped("shutdown"))
        assertThat(interruptedAfter.get()).isTrue()
        verify { recorder.completed(JobId.FX_INGESTION, any(), JobRunOutcome.SKIPPED, any(), "shutdown") }
        verify(exactly = 0) { alert.send(any()) }
    }

    @Test
    fun `종료 중 lock 획득 예외는 shutdown 으로 기록하고 알리지 않는다`() {
        every { lock.tryAcquire(any(), any(), any(), any()) } answers {
            executor.onApplicationEvent(closedEvent)
            throw IllegalStateException("LettuceConnectionFactory is STOPPING")
        }

        val result = executor.execute(config) { JobResult.Success }

        assertThat(result).isEqualTo(JobResult.Skipped("shutdown"))
        verify { recorder.completed(JobId.FX_INGESTION, any(), JobRunOutcome.SKIPPED, clock.instant(), "shutdown") }
        verify(exactly = 0) { recorder.completed(any(), any(), JobRunOutcome.FAILED, any(), any()) }
        verify(exactly = 0) { alert.send(any()) }
    }

    @Test
    fun `종료 중 lock 해제 예외는 shutdown 으로 기록하고 알리지 않는다`() {
        every { lock.release(any(), any()) } throws IllegalStateException("LettuceConnectionFactory is STOPPING")

        val result = executor.execute(config) {
            executor.onApplicationEvent(closedEvent)
            JobResult.Success
        }

        assertThat(result).isEqualTo(JobResult.Skipped("shutdown"))
        verify(exactly = 1) { lock.release("lock:fx", any()) }
        verify { recorder.completed(JobId.FX_INGESTION, any(), JobRunOutcome.SKIPPED, clock.instant(), "shutdown") }
        verify(exactly = 0) { recorder.completed(any(), any(), JobRunOutcome.FAILED, any(), any()) }
        verify(exactly = 0) { alert.send(any()) }
    }

    @Test
    fun `종료 신호가 없으면 같은 예외는 FAILED 로 기록하고 알린다`() {
        val result = executor.execute(config) { throw RuntimeException("LettuceConnectionFactory is STOPPING") }

        assertThat(result).isInstanceOf(JobResult.Failure::class.java)
        verify { recorder.completed(JobId.FX_INGESTION, any(), JobRunOutcome.FAILED, clock.instant(), "RuntimeException") }
        verify(exactly = 1) { alert.send(any()) }
    }

    @Test
    fun `컨텍스트 종료 시 lifecycle stop 보다 먼저 shutdown 으로 분류한다`() {
        val stopResult = AtomicReference<JobResult>()
        val contextExecutor = JobExecutor(lock, recorder, alert, clock)
        val context = GenericApplicationContext()
        context.registerBean(JobExecutor::class.java, Supplier { contextExecutor })
        context.registerBean(
            "lateLifecycle",
            SmartLifecycle::class.java,
            Supplier<SmartLifecycle> {
                object : SmartLifecycle {
                    private var running = false

                    override fun start() {
                        running = true
                    }

                    override fun stop() {
                        stopResult.set(contextExecutor.execute(config) { JobResult.Success })
                        running = false
                    }

                    override fun isRunning(): Boolean = running

                    override fun getPhase(): Int = Int.MAX_VALUE
                }
            },
        )

        context.refresh()
        context.close()
        contextExecutor.close()

        assertThat(stopResult.get()).isEqualTo(JobResult.Skipped("shutdown"))
        verify(exactly = 0) { lock.tryAcquire(any(), any(), any(), any()) }
    }

    @Test
    fun `자식 컨텍스트 종료 이벤트에는 반응하지 않는다`() {
        val parentExecutor = JobExecutor(lock, recorder, alert, clock)
        val parent = GenericApplicationContext()
        parent.registerBean(JobExecutor::class.java, Supplier { parentExecutor })
        parent.refresh()
        try {
            val child = GenericApplicationContext()
            child.setParent(parent)
            child.refresh()
            child.close()

            var invoked = false
            val result = parentExecutor.execute(config) {
                invoked = true
                JobResult.Success
            }

            assertThat(result).isEqualTo(JobResult.Success)
            assertThat(invoked).isTrue()
            verify { recorder.completed(JobId.FX_INGESTION, any(), JobRunOutcome.SUCCEEDED, any(), any()) }
        } finally {
            parent.close()
            parentExecutor.close()
        }
    }

    @Test
    fun `종료 중 성공한 action 은 SUCCEEDED 로 기록한다`() {
        val result = executor.execute(config) {
            executor.onApplicationEvent(closedEvent)
            JobResult.Success
        }

        assertThat(result).isEqualTo(JobResult.Success)
        verify { recorder.completed(JobId.FX_INGESTION, any(), JobRunOutcome.SUCCEEDED, any(), any()) }
        verify(exactly = 0) { recorder.completed(any(), any(), JobRunOutcome.FAILED, any(), any()) }
        verify(exactly = 0) { alert.send(any()) }
    }

    private fun awaitIgnoringInterrupt(latch: CountDownLatch) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            try {
                if (latch.await(100, TimeUnit.MILLISECONDS)) return
            } catch (_: InterruptedException) {
                // 취소를 무시하는 외부 I/O를 재현한다.
            }
        }
    }

    private fun runFailingDuringShutdown(failure: Exception): JobResult {
        val started = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val outcome = AtomicReference<JobResult>()
        val caller = Thread {
            outcome.set(
                executor.execute(config) {
                    started.countDown()
                    proceed.await()
                    throw failure
                },
            )
        }
        caller.start()
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue()

        executor.onApplicationEvent(closedEvent)
        proceed.countDown()
        caller.join(5_000)

        assertThat(caller.isAlive).isFalse()
        return outcome.get()
    }
}
