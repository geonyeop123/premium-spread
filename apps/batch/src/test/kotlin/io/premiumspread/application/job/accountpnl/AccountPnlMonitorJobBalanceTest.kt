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
import io.premiumspread.domain.accountpnl.ForeignPerpBalance
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

/** 잔고 조회 실패(null)가 Job 결과·알림 저장·중단 판정에 영향을 주지 않는다는 회귀 방어선. */
class AccountPnlMonitorJobBalanceTest {
    private val t0 = Instant.parse("2026-10-20T03:00:30Z")
    private val clock = MutableClock(t0)
    private val executor = mockk<JobExecutor>()
    private val korea = FakeKorea(clock)
    private val foreign = FakeForeign(clock)
    private val store = FakeStore()
    private val sender = FakeSender()
    private val job =
        AccountPnlMonitorJob(
            korea,
            foreign,
            FakeFx(clock),
            FakeLastSuccess(clock),
            store,
            sender,
            executor,
            clock,
            hourlyReportEnabled = true,
        )

    @BeforeEach
    fun setUp() {
        every { executor.execute(any(), any()) } answers { secondArg<() -> JobResult>().invoke() }
    }

    private fun keys() = store.rows.map { it.mail.eventKey }

    private fun profitBody() = store.rows.map { it.mail }.first { it.eventKey.startsWith("account-pnl-profit-") }.body

    @Test
    fun `잔고와 USDT 가격이 없어도 성공하고 수익 메일과 정시 보고를 저장하며 자산은 없음으로 표시한다`() {
        assertThat(job.run()).isInstanceOf(JobResult.Success::class.java)

        assertThat(keys()).anyMatch { it.startsWith("account-pnl-profit-") }
        assertThat(keys()).anyMatch { it.startsWith("account-pnl-hourly-") }
        assertThat(profitBody()).contains("■ 자산").contains("총 자산               없음")
    }

    @Test
    fun `잔고가 계속 없어도 6분 동안 모든 회차가 성공하고 중단 알림이 없으며 마지막 성공 시각이 갱신된다`() {
        repeat(7) { index ->
            if (index > 0) clock.advance(Duration.ofMinutes(1))
            assertThat(job.run()).isInstanceOf(JobResult.Success::class.java)
        }

        assertThat(keys()).noneMatch { it.startsWith("account-pnl-outage-") }
        assertThat(store.state.outage.lastSuccessAt).isEqualTo(clock.instant())
    }

    @Test
    fun `잔고를 채우면 자산 섹션에 없음이 나타나지 않는다`() {
        korea.krwBalance = BigDecimal("1000000")
        korea.usdtPrice = BigDecimal("1412")
        foreign.balance = ForeignPerpBalance(BigDecimal("1000"), BigDecimal("900"))

        assertThat(job.run()).isInstanceOf(JobResult.Success::class.java)

        val body = profitBody()
        val section = body.substring(body.indexOf("■ 자산")).lines().drop(1).takeWhile { !it.startsWith("■") }
        assertThat(section).isNotEmpty
        assertThat(section.joinToString("\n")).doesNotContain("없음")
    }
}
