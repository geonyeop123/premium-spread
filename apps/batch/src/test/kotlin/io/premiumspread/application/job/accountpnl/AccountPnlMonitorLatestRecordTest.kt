package io.premiumspread.application.job.accountpnl

import io.mockk.every
import io.mockk.mockk
import io.premiumspread.application.common.JobExecutor
import io.premiumspread.application.common.JobResult
import io.premiumspread.application.job.accountpnl.AccountPnlMonitorJobTest.FakeForeign
import io.premiumspread.application.job.accountpnl.AccountPnlMonitorJobTest.FakeFx
import io.premiumspread.application.job.accountpnl.AccountPnlMonitorJobTest.FakeKorea
import io.premiumspread.application.job.accountpnl.AccountPnlMonitorJobTest.FakeLastSuccess
import io.premiumspread.application.job.accountpnl.AccountPnlMonitorJobTest.FakeSender
import io.premiumspread.application.job.accountpnl.AccountPnlMonitorJobTest.FakeStore
import io.premiumspread.application.job.accountpnl.AccountPnlMonitorJobTest.MutableClock
import io.premiumspread.domain.accountpnl.AccountPnlLatest
import io.premiumspread.domain.accountpnl.AccountPnlLatestStore
import io.premiumspread.domain.accountpnl.AccountReadException
import io.premiumspread.domain.accountpnl.AccountReadFailureCategory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

/** PnL 모니터의 최신 순수익률 기록 계약 (dod AC11, design §6.3). */
@ExtendWith(OutputCaptureExtension::class)
class AccountPnlMonitorLatestRecordTest {
    private val t0 = Instant.parse("2026-10-20T03:00:00Z")
    private val clock = MutableClock(t0)
    private val executor = mockk<JobExecutor>()

    private lateinit var korea: FakeKorea
    private lateinit var foreign: FakeForeign
    private lateinit var store: FakeStore
    private lateinit var sender: FakeSender
    private lateinit var latest: FakeAccountPnlLatestStore
    private lateinit var job: AccountPnlMonitorJob

    @BeforeEach
    fun setUp() {
        every { executor.execute(any(), any()) } answers { secondArg<() -> JobResult>().invoke() }
        korea = FakeKorea(clock)
        foreign = FakeForeign(clock)
        store = FakeStore()
        sender = FakeSender()
        latest = FakeAccountPnlLatestStore()
        job = newJob(latest)
    }

    @AfterEach
    fun clearInterrupt() {
        Thread.interrupted()
    }

    private fun newJob(latestPnl: AccountPnlLatestStore?) = AccountPnlMonitorJob(
        korea,
        foreign,
        FakeFx(clock),
        FakeLastSuccess(clock),
        store,
        sender,
        executor,
        clock,
        latestPnl = latestPnl,
    )

    @Test
    fun `Computed 회차는 저장 성공 뒤 computed 값을 한 번 기록한다`() {
        assertThat(job.run()).isEqualTo(JobResult.Success)

        assertThat(latest.records).hasSize(1)
        val recorded = latest.records.single()
        assertThat(recorded.position).isTrue()
        assertThat(recorded.netPercent).isEqualByComparingTo("1.96")
        assertThat(recorded.netKrw).isNotNull()
        assertThat(recorded.netKrw!!.scale()).isZero()
        assertThat(recorded.calculatedAt).isEqualTo(t0)
        assertThat(sender.sent).hasSize(1)
    }

    @Test
    fun `NoPosition 회차는 조회 뒤 시각으로 noPosition 을 한 번 기록한다`() {
        korea.quantity = BigDecimal.ZERO
        foreign.onRead = { clock.advance(Duration.ofSeconds(1)) }

        assertThat(job.run()).isEqualTo(JobResult.Success)

        assertThat(latest.records).containsExactly(AccountPnlLatest.noPosition(t0.plusSeconds(1)))
    }

    @Test
    fun `Failed 회차는 기록하지 않는다`() {
        korea.failure = AccountReadException(AccountReadFailureCategory.UNEXPECTED, "down")

        assertThat(job.run()).isEqualTo(JobResult.Success)

        assertThat(latest.records).isEmpty()
    }

    @Test
    fun `save 가 false 이면 기록하지 않는다`() {
        store.concurrentWriteBeforeSave = true

        assertThat(job.run()).isInstanceOf(JobResult.Skipped::class.java)

        assertThat(latest.records).isEmpty()
    }

    @Test
    fun `save 가 예외면 기록하지 않는다`() {
        store.failOn = "save"

        assertThat(job.run()).isInstanceOf(JobResult.Failure::class.java)

        assertThat(latest.records).isEmpty()
    }

    @Test
    fun `저장 전 취소면 기록하지 않는다`() {
        korea.onRead = { Thread.currentThread().interrupt() }

        assertThat(job.run()).isInstanceOf(JobResult.Skipped::class.java)

        assertThat(latest.records).isEmpty()
    }

    @Test
    fun `record 예외는 삼키고 결과와 발송은 예외 없는 회차와 같다`(output: CapturedOutput) {
        latest.nextFailure = IllegalStateException("secret-detail")

        val result = job.run()

        assertThat(result).isEqualTo(JobResult.Success)
        assertThat(sender.sent).hasSize(1)
        val warn = output.all.lines().single { it.contains("Account PnL latest record failed") }
        assertThat(warn).endsWith("error=IllegalStateException")
        assertThat(output.all).doesNotContain("secret-detail")
    }

    @Test
    fun `record 가 취소되면 interrupt 를 복구하고 기록 없이 끝낸다`() {
        latest.nextFailure = InterruptedException()

        job.run()

        assertThat(Thread.currentThread().isInterrupted).isTrue()
        assertThat(latest.records).isEmpty()
    }

    @Test
    fun `latestPnl 이 없으면 기존 결과와 같다`() {
        val result = newJob(null).run()

        assertThat(result).isEqualTo(JobResult.Success)
        assertThat(sender.sent).hasSize(1)
        assertThat(latest.records).isEmpty()
    }

    private class FakeAccountPnlLatestStore : AccountPnlLatestStore {
        val records = mutableListOf<AccountPnlLatest>()
        var nextFailure: Exception? = null

        override fun record(latest: AccountPnlLatest) {
            nextFailure?.let {
                nextFailure = null
                throw it
            }
            records += latest
        }

        override fun find(): AccountPnlLatest? = records.lastOrNull()
    }
}
