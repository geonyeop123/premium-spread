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
import io.premiumspread.domain.accountpnl.ForeignPerpAccountReadPort
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import java.math.BigDecimal
import java.time.Instant

/** 회차별 순손익 INFO 로그 계약. 사용자가 배치 로그만 읽고 현재 순손익을 알 수 있어야 한다. */
@ExtendWith(OutputCaptureExtension::class)
class AccountPnlMonitorJobObservationLogTest {
    private val t0 = Instant.parse("2026-10-20T03:00:00Z")
    private val clock = MutableClock(t0)
    private val executor = mockk<JobExecutor>()
    private val korea = FakeKorea(clock)
    private val foreign = FakeForeign(clock)
    private lateinit var job: AccountPnlMonitorJob

    @BeforeEach
    fun setUp() {
        every { executor.execute(any(), any()) } answers { secondArg<() -> JobResult>().invoke() }
        job = newJob(foreignPort = foreign)
    }

    private fun newJob(foreignPort: ForeignPerpAccountReadPort) = AccountPnlMonitorJob(
        korea,
        foreignPort,
        FakeFx(clock),
        FakeLastSuccess(clock),
        FakeStore(),
        FakeSender(),
        executor,
        clock,
    )

    private fun observedLines(output: CapturedOutput) = output.all.lines().filter { "Account PnL observed" in it }

    @Test
    fun `계산된 회차는 순손익 INFO 한 줄을 정해진 형식으로 남긴다`(output: CapturedOutput) {
        job.run()

        val line = observedLines(output).single()
        assertThat(line).contains(
            "Account PnL observed: netPercent=1.96%, netKrw=20000, netPercentAtLast=1.96%, netKrwAtLast=20000, " +
                "grossKrw=20000, entryFeeKrw=0, fundingKrw=0, exitFeeEstimateKrw=0, " +
                "koreaQty=0.01, koreaExit=102000000, foreignQty=0.01, foreignExit=70000, fxRate=1400, " +
                "source=EXCHANGE_API, unrecovered=[], calculatedAt=2026-10-20T03:00:00Z",
        )
    }

    @Test
    fun `미확보 항목은 unrecovered 목록에 이름으로 남는다`(output: CapturedOutput) {
        val unrecovered = ForeignPerpAccountReadPort {
            foreign.read().copy(entryCommissionUsdt = null, fundingUsdt = null)
        }

        newJob(unrecovered).run()

        assertThat(observedLines(output).single()).contains("unrecovered=[foreignEntryFee, funding]")
    }

    @Test
    fun `포지션이 없는 회차에는 순손익 로그가 없다`(output: CapturedOutput) {
        foreign.signedQuantity = BigDecimal.ZERO

        job.run()

        assertThat(observedLines(output)).isEmpty()
    }

    @Test
    fun `관측 실패 회차에는 순손익 로그가 없다`(output: CapturedOutput) {
        korea.failure = AccountReadException(AccountReadFailureCategory.UNEXPECTED, "boom")

        job.run()

        assertThat(observedLines(output)).isEmpty()
    }
}
