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
import io.premiumspread.domain.accountpnl.AccountReadException
import io.premiumspread.domain.accountpnl.AccountReadFailureCategory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

/** 정시 보고 메일: 1% 알림과 무관하게 계산 성공 회차에서 시간대 버킷당 한 통 (eventKey 중복 무시에 의존). */
class AccountPnlMonitorJobHourlyReportTest {
    // 2026-10-20 12:00:30 KST — 정시 직후
    private val t0 = Instant.parse("2026-10-20T03:00:30Z")
    private val clock = MutableClock(t0)
    private val executor = mockk<JobExecutor>()
    private val korea = FakeKorea(clock)
    private val foreign = FakeForeign(clock)
    private val fx = FakeFx(clock)
    private val lastSuccess = FakeLastSuccess(clock)
    private val store = FakeStore()
    private val sender = FakeSender()

    @BeforeEach
    fun setUp() {
        every { executor.execute(any(), any()) } answers { secondArg<() -> JobResult>().invoke() }
        korea.bid = BigDecimal("100500000") // 0.50% — 1% 알림 기준 미만
    }

    private fun job(enabled: Boolean = true) =
        AccountPnlMonitorJob(korea, foreign, fx, lastSuccess, store, sender, executor, clock, hourlyReportEnabled = enabled)

    private fun hourlyKeys() = store.rows.map { it.mail.eventKey }.filter { it.startsWith("account-pnl-hourly-") }

    @Test
    fun `계산 성공 회차는 1퍼센트 미만이어도 정시 보고를 한 통 저장하고 보낸다`() {
        job().run()

        assertThat(hourlyKeys()).containsExactly("account-pnl-hourly-1792465200000")
        assertThat(sender.sent.single().subject).isEqualTo("[실계정] 정시 보고 12:00 +0.50% +5,000원")
    }

    @Test
    fun `같은 시간대의 다음 회차는 새 정시 보고를 만들지 않고 다음 시간대는 새 키다`() {
        val job = job()
        job.run()
        clock.advance(Duration.ofMinutes(1))
        job.run()
        assertThat(hourlyKeys()).hasSize(1)

        clock.advance(Duration.ofHours(1))
        job.run()
        assertThat(hourlyKeys()).containsExactly("account-pnl-hourly-1792465200000", "account-pnl-hourly-1792468800000")
    }

    @Test
    fun `포지션이 없는 회차는 정시 보고가 없다`() {
        foreign.signedQuantity = BigDecimal.ZERO

        job().run()

        assertThat(hourlyKeys()).isEmpty()
    }

    @Test
    fun `관측 실패 회차는 정시 보고가 없다`() {
        korea.failure = AccountReadException(AccountReadFailureCategory.NETWORK, "down")

        job().run()

        assertThat(hourlyKeys()).isEmpty()
    }

    @Test
    fun `꺼져 있으면 정시 보고가 없다`() {
        job(enabled = false).run()

        assertThat(store.rows).isEmpty()
    }

    @Test
    fun `1퍼센트 이상이면 수익 메일과 정시 보고를 모두 저장한다`() {
        korea.bid = BigDecimal("102000000")

        job().run()

        assertThat(store.rows.map { it.mail.eventKey }).anyMatch { it.startsWith("account-pnl-profit-") }
        assertThat(hourlyKeys()).hasSize(1)
        assertThat(store.rows).hasSize(2)
    }

    @Test
    fun `버전 충돌로 저장이 건너뛰어지면 다음 회차에 다시 시도한다`() {
        val job = job()
        store.concurrentWriteBeforeSave = true
        assertThat(job.run()).isInstanceOf(JobResult.Skipped::class.java)
        assertThat(hourlyKeys()).isEmpty()

        store.concurrentWriteBeforeSave = false
        job.run()

        assertThat(hourlyKeys()).hasSize(1)
    }
}
