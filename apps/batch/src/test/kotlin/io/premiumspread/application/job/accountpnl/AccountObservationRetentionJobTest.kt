package io.premiumspread.application.job.accountpnl

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.premiumspread.application.common.JobConfig
import io.premiumspread.application.common.JobExecutor
import io.premiumspread.application.common.JobResult
import io.premiumspread.domain.accountpnl.AccountObservationRetentionStore
import io.premiumspread.domain.job.JobId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/** 관측 보존 정리 Job (#148 dod AC3). store·executor 는 가짜다. */
@ExtendWith(OutputCaptureExtension::class)
class AccountObservationRetentionJobTest {

    private val store = FakeStore()
    private val configs = mutableListOf<JobConfig>()

    private fun job(settings: AccountObservationRetentionJob.Settings = SETTINGS) = AccountObservationRetentionJob(
        store = store,
        executor = recordingExecutor(),
        clock = Clock.fixed(NOW_WITH_NANOS, ZoneOffset.UTC),
        settings = settings,
    )

    @AfterEach
    fun clearInterrupt() {
        Thread.interrupted()
    }

    @Test
    fun `cutoff 는 현재 시각에서 보존 기간을 뺀 값이다`() {
        store.results.add(0)

        assertThat(job().run()).isEqualTo(JobResult.Success)

        // DB 는 DATETIME(6) 이라 마이크로초로 자른다 (2027-03-01 − 365d = 2026-03-01)
        assertThat(store.calls).containsExactly(Instant.parse("2026-03-01T00:00:00.123456Z") to 1000)
    }

    @Test
    fun `배치가 batch-size 보다 적게 지우면 멈춘다`(output: CapturedOutput) {
        store.results.addAll(listOf(1000, 1000, 400))

        assertThat(job().run()).isEqualTo(JobResult.Success)

        assertThat(store.calls).hasSize(3)
        assertThat(output.all).doesNotContain("safety cap")
    }

    @Test
    fun `max-batches-per-run 에 닿으면 더 부르지 않고 경고한다`(output: CapturedOutput) {
        store.results.addAll(listOf(1000, 1000, 1000, 1000))

        assertThat(job(SETTINGS.copy(maxBatchesPerRun = 3)).run()).isEqualTo(JobResult.Success)

        assertThat(store.calls).hasSize(3)
        assertThat(output.all).contains("safety cap").contains("deleted=3000").contains("maxBatches=3")
    }

    @Test
    fun `JobId ACCOUNT_OBSERVATION_RETENTION 설정으로 executor 를 거친다`() {
        store.results.add(0)

        job().run()

        assertThat(configs.single().jobId).isEqualTo(JobId.ACCOUNT_OBSERVATION_RETENTION)
        assertThat(configs.single().lockKey).isEqualTo("lock:account-pnl:observation-retention")
    }

    @Test
    fun `store 예외면 Failure 다`() {
        store.failure = IllegalStateException("db down")

        val result = job().run()

        assertThat(result).isInstanceOf(JobResult.Failure::class.java)
        assertThat((result as JobResult.Failure).exception).isSameAs(store.failure)
    }

    @Test
    fun `cause 체인에 InterruptedException 이 있으면 다시 던지고 interrupt 를 되살린다`() {
        store.failure = IllegalStateException("wrapped", RuntimeException("mid", InterruptedException("stop")))

        assertThatThrownBy { job().run() }.isSameAs(store.failure)

        assertThat(Thread.currentThread().isInterrupted).isTrue()
    }

    @Test
    fun `CancellationException 도 다시 던지고 interrupt 를 되살린다`() {
        store.failure = java.util.concurrent.CancellationException("cancelled")

        assertThatThrownBy { job().run() }.isSameAs(store.failure)

        assertThat(Thread.currentThread().isInterrupted).isTrue()
    }

    @Test
    fun `interrupt 가 서 있으면 다음 배치를 부르지 않는다`() {
        store.results.addAll(listOf(1000, 1000))
        store.onCall = { Thread.currentThread().interrupt() }

        assertThat(job().run()).isInstanceOf(JobResult.Skipped::class.java)

        assertThat(store.calls).hasSize(1)
    }

    private fun recordingExecutor(): JobExecutor {
        val executor = mockk<JobExecutor>()
        val config = slot<JobConfig>()
        every { executor.execute(capture(config), any()) } answers {
            configs.add(config.captured)
            secondArg<() -> JobResult>().invoke()
        }
        return executor
    }

    class FakeStore : AccountObservationRetentionStore {
        val results = ArrayDeque<Int>()
        val calls = mutableListOf<Pair<Instant, Int>>()
        var failure: Exception? = null
        var onCall: () -> Unit = {}

        override fun deleteObservedBefore(cutoff: Instant, limit: Int): Int {
            calls.add(cutoff to limit)
            onCall()
            failure?.let { throw it }
            return results.removeFirst()
        }
    }

    private companion object {
        val NOW_WITH_NANOS: Instant = Instant.parse("2027-03-01T00:00:00.123456789Z")
        val SETTINGS = AccountObservationRetentionJob.Settings(Duration.ofDays(365), 1000, 50)
    }
}
