package io.premiumspread.application.job.tradeprep

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.premiumspread.application.common.JobConfig
import io.premiumspread.application.common.JobExecutor
import io.premiumspread.application.common.JobResult
import io.premiumspread.domain.job.JobId
import io.premiumspread.domain.tradeprep.BalanceBasis
import io.premiumspread.domain.tradeprep.BalanceSnapshot
import io.premiumspread.domain.tradeprep.TradePreparationReconcileOutcome
import io.premiumspread.domain.tradeprep.TradePreparationReconcileService
import io.premiumspread.domain.tradeprep.TradePreparationReconcileSummary
import io.premiumspread.domain.tradeprep.VerifiedBalance
import io.premiumspread.domain.tradeprep.VerifiedBalanceReadPort
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * Job 은 판정하지 않는다 — 판정용 port 를 해석하고 Domain capability 를 부른 뒤 결과를 `JobResult`
 * 로 옮길 뿐이라는 것을 고정한다 (design.md D21·D22).
 */
class TradePreparationReconcileJobTest {

    private val balanceSource = mockk<ObjectProvider<VerifiedBalanceReadPort>>()
    private val reconcileService = mockk<TradePreparationReconcileService>()
    private val executor = mockk<JobExecutor>()
    private val now = Instant.parse("2026-08-30T00:00:10Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private lateinit var job: TradePreparationReconcileJob

    @BeforeEach
    fun setUp() {
        every { executor.execute(any(), any()) } answers { secondArg<() -> JobResult>().invoke() }
        job = TradePreparationReconcileJob(balanceSource, reconcileService, executor, clock)
    }

    @Test
    fun `판정용 원천이 배선돼 있으면 그 잔고와 주입한 clock 으로 Domain 대조를 호출한다`() {
        val balance = verifiedBalance()
        every { balanceSource.getIfAvailable() } returns VerifiedBalanceReadPort { balance }
        every { reconcileService.reconcile(balance, now) } returns
            TradePreparationReconcileSummary.reconciled(examined = 2, invalidated = 1)

        assertThat(job.run()).isEqualTo(JobResult.Success)

        verify(exactly = 1) { reconcileService.reconcile(balance, now) }
    }

    /**
     * production 의 정상 상태다 (D22). **failure 가 아니고, 대조하지 못했다고 계획을 무효화하지도
     * 않는다** — Domain 대조를 아예 부르지 않았음을 단언해 그 사실을 고정한다.
     */
    @Test
    fun `판정용 원천이 없으면 대조하지 않고 bounded skip 사유로 끝낸다`() {
        every { balanceSource.getIfAvailable() } returns null

        val result = job.run()

        assertThat((result as JobResult.Skipped).reason).isEqualTo("balance_source_unavailable")
        verify(exactly = 0) { reconcileService.reconcile(any(), any()) }
    }

    @Test
    fun `원천이 판정용 잔고를 주지 못하면 Domain 이 판정하도록 그대로 넘긴다`() {
        every { balanceSource.getIfAvailable() } returns VerifiedBalanceReadPort { null }
        every { reconcileService.reconcile(null, now) } returns
            TradePreparationReconcileSummary.notReconciled(TradePreparationReconcileOutcome.BALANCE_UNAVAILABLE)

        val result = job.run()

        assertThat((result as JobResult.Skipped).reason).isEqualTo("balance_unavailable")
        verify(exactly = 1) { reconcileService.reconcile(null, now) }
    }

    @Test
    fun `대조 중 예외는 Failure 로 보고한다`() {
        every { balanceSource.getIfAvailable() } returns VerifiedBalanceReadPort { verifiedBalance() }
        every { reconcileService.reconcile(any(), any()) } throws IllegalStateException("db down")

        assertThat((job.run() as JobResult.Failure).exception).isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `typed JobConfig 로 lock 과 timeout 을 적용한다`() {
        val config = JobConfig(
            JobId.TRADE_PREPARATION_RECONCILE,
            "lock:custom",
            Duration.ofSeconds(9),
            Duration.ofSeconds(4),
        )
        val configured = TradePreparationReconcileJob(balanceSource, reconcileService, executor, clock) { requested ->
            if (requested == JobId.TRADE_PREPARATION_RECONCILE) config else error("unexpected $requested")
        }
        every { balanceSource.getIfAvailable() } returns null

        configured.run()

        verify(exactly = 1) { executor.execute(config, any()) }
    }

    private fun verifiedBalance(): VerifiedBalance = VerifiedBalance.from(
        BalanceSnapshot(
            id = "recorded-1",
            koreaBalance = BigDecimal("1000000"),
            foreignBalance = BigDecimal("700"),
            balanceBasis = BalanceBasis.FRESH,
            observedAt = now,
        ),
    )!!
}
