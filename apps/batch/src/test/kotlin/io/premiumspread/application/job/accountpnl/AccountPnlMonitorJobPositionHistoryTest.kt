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
import io.premiumspread.domain.accountpnl.AccountPositionHistoryStore
import io.premiumspread.domain.accountpnl.AccountReadException
import io.premiumspread.domain.accountpnl.AccountReadFailureCategory
import io.premiumspread.domain.accountpnl.ForeignLeg
import io.premiumspread.domain.accountpnl.ForeignPerpPositionSource
import io.premiumspread.domain.accountpnl.KoreaLeg
import io.premiumspread.domain.accountpnl.PositionHistoryRound
import io.premiumspread.domain.accountpnl.PositionLegs
import io.premiumspread.domain.accountpnl.PriceLevel
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

/** 실계정 모니터의 포지션 이력 기록 계약 (dod AC4·AC5, design D5). */
@ExtendWith(OutputCaptureExtension::class)
class AccountPnlMonitorJobPositionHistoryTest {
    private val t0 = Instant.parse("2026-10-20T03:00:00Z")
    private val executor = mockk<JobExecutor>()

    private lateinit var clock: MutableClock
    private lateinit var korea: FakeKorea
    private lateinit var foreign: FakeForeign
    private lateinit var store: FakeStore
    private lateinit var sender: FakeSender
    private lateinit var history: FakeHistoryStore

    @BeforeEach
    fun setUp() {
        every { executor.execute(any(), any()) } answers { secondArg<() -> JobResult>().invoke() }
        reset()
    }

    @AfterEach
    fun clearInterrupt() {
        Thread.interrupted()
    }

    private fun reset() {
        clock = MutableClock(t0)
        korea = FakeKorea(clock)
        foreign = FakeForeign(clock)
        store = FakeStore()
        sender = FakeSender()
        history = FakeHistoryStore()
    }

    private fun newJob(positionHistory: AccountPositionHistoryStore?) = AccountPnlMonitorJob(
        korea,
        foreign,
        FakeFx(clock),
        FakeLastSuccess(clock),
        store,
        sender,
        executor,
        clock,
        positionHistory = positionHistory,
    )

    private val expectedLegs = PositionLegs(
        KoreaLeg.of(BigDecimal("0.01"), BigDecimal("100000000")),
        ForeignLeg.of(BigDecimal("-0.01"), BigDecimal("70000")),
    )

    @Test
    fun `Computed 회차는 pnl 과 시세를 담은 round 를 한 번 기록하고 관측 시각은 pnl 계산 시각이다`() {
        foreign.onRead = { clock.advance(Duration.ofSeconds(2)) }

        assertThat(newJob(history).run()).isEqualTo(JobResult.Success)

        assertThat(history.rounds).hasSize(1)
        val round = history.rounds.single()
        val pnl = requireNotNull(round.pnl)
        assertThat(round.observedAt).isEqualTo(t0.plusSeconds(2))
        assertThat(round.observedAt).isEqualTo(pnl.calculatedAt)
        assertThat(round.legs).isEqualTo(expectedLegs)
        assertThat(round.source).isEqualTo(ForeignPerpPositionSource.EXCHANGE_API)
        assertThat(round.market.fxRate).isEqualByComparingTo("1400")
        assertThat(round.market.fxRate).isEqualByComparingTo(pnl.fxRate)
        assertThat(round.market.koreaBidLevels).containsExactly(PriceLevel(korea.bid, korea.bidDepth))
        assertThat(round.market.foreignAskLevels)
            .containsExactly(PriceLevel(BigDecimal("70000"), BigDecimal("1")))
    }

    @Test
    fun `NoPosition 회차는 pnl 없이 시세를 담은 round 를 한 번 기록한다`() {
        korea.quantity = BigDecimal.ZERO
        foreign.onRead = { clock.advance(Duration.ofSeconds(1)) }

        assertThat(newJob(history).run()).isEqualTo(JobResult.Success)

        assertThat(history.rounds).hasSize(1)
        val round = history.rounds.single()
        assertThat(round.pnl).isNull()
        assertThat(round.observedAt).isEqualTo(t0.plusSeconds(1))
        assertThat(round.legs.korea).isEqualTo(KoreaLeg.of(BigDecimal.ZERO, BigDecimal("100000000")))
        assertThat(round.market.fxRate).isEqualByComparingTo("1400")
        assertThat(round.market.koreaBidLevels).containsExactly(PriceLevel(korea.bid, korea.bidDepth))
        assertThat(round.market.foreignAskLevels).hasSize(1)
    }

    @Test
    fun `관측 실패 회차는 기록하지 않는다`() {
        korea.failure = AccountReadException(AccountReadFailureCategory.UNEXPECTED, "down")

        assertThat(newJob(history).run()).isEqualTo(JobResult.Success)

        assertThat(history.calls).isZero()
    }

    @Test
    fun `skipped - save 가 false 이면 기록하지 않는다`() {
        store.concurrentWriteBeforeSave = true

        assertThat(newJob(history).run()).isInstanceOf(JobResult.Skipped::class.java)

        assertThat(history.calls).isZero()
    }

    @Test
    fun `skipped - 저장 전 취소면 기록하지 않는다`() {
        korea.onRead = { Thread.currentThread().interrupt() }

        assertThat(newJob(history).run()).isInstanceOf(JobResult.Skipped::class.java)

        assertThat(history.calls).isZero()
    }

    @Test
    fun `save 가 예외면 기록하지 않는다`() {
        store.failOn = "save"

        assertThat(newJob(history).run()).isInstanceOf(JobResult.Failure::class.java)

        assertThat(history.calls).isZero()
    }

    @Test
    fun `historyFailure - store 예외는 삼키고 결과·상태·outbox·발송은 이력 없는 Job 과 같다`(output: CapturedOutput) {
        val baseline = runScenario(null)

        reset()
        history.failure = RuntimeException("secret-detail qty=0.01")
        val failed = runScenario(history)

        assertThat(history.calls).isEqualTo(1)
        assertThat(failed).isEqualTo(baseline)
        val warn = output.all.lines().filter { it.contains("Account position history record failed") }
        assertThat(warn).hasSize(1)
        assertThat(warn.single()).endsWith("error=RuntimeException")
        assertThat(output.all).doesNotContain("secret-detail")
        assertThat(Thread.currentThread().isInterrupted).isFalse()
    }

    @Test
    fun `historyFailure - store 가 감싼 취소는 interrupt 를 복원하고 경고하지 않는다`(output: CapturedOutput) {
        history.failure = RuntimeException(InterruptedException())

        newJob(history).run()

        assertThat(Thread.currentThread().isInterrupted).isTrue()
        assertThat(history.calls).isEqualTo(1)
        assertThat(output.all).doesNotContain("Account position history record failed")
    }

    private data class Outcome(
        val result: JobResult,
        val state: Any,
        val rows: List<Pair<Any, Any>>,
        val sentSubjects: List<String>,
    )

    private fun runScenario(positionHistory: AccountPositionHistoryStore?): Outcome {
        val result = newJob(positionHistory).run()
        return Outcome(
            result,
            store.state,
            store.rows.map { it.mail to it.status },
            sender.sent.map { it.subject },
        )
    }

    private class FakeHistoryStore : AccountPositionHistoryStore {
        val rounds = mutableListOf<PositionHistoryRound>()
        var calls = 0
        var failure: Exception? = null

        override fun record(round: PositionHistoryRound) {
            calls += 1
            failure?.let { throw it }
            rounds += round
        }
    }
}
