package io.premiumspread.application.job.accountpnl

import io.mockk.every
import io.mockk.mockk
import io.premiumspread.application.common.JobExecutor
import io.premiumspread.application.common.JobResult
import io.premiumspread.domain.accountpnl.AccountReadException
import io.premiumspread.domain.accountpnl.AccountReadFailureCategory
import io.premiumspread.domain.accountpnl.AccountRealizedPnlStore
import io.premiumspread.domain.accountpnl.AppliedFx
import io.premiumspread.domain.accountpnl.FillHistoryWindowLimitException
import io.premiumspread.domain.accountpnl.FillSide
import io.premiumspread.domain.accountpnl.ForeignFunding
import io.premiumspread.domain.accountpnl.ForeignLeg
import io.premiumspread.domain.accountpnl.ForeignPerpFill
import io.premiumspread.domain.accountpnl.ForeignPerpFillReadPort
import io.premiumspread.domain.accountpnl.ForeignPerpPositionSource
import io.premiumspread.domain.accountpnl.KoreaLeg
import io.premiumspread.domain.accountpnl.KoreaSpotFill
import io.premiumspread.domain.accountpnl.KoreaSpotFillReadPort
import io.premiumspread.domain.accountpnl.PositionLegs
import io.premiumspread.domain.accountpnl.RealizedPnlPolicy
import io.premiumspread.domain.accountpnl.SettlementFailure
import io.premiumspread.domain.accountpnl.SettlementOutcome
import io.premiumspread.domain.accountpnl.SettlementStatus
import io.premiumspread.domain.accountpnl.SettlementTarget
import io.premiumspread.domain.exchangerate.ExchangeRateSnapshot
import io.premiumspread.domain.job.JobId
import io.premiumspread.domain.job.JobLastSuccessReadPort
import io.premiumspread.domain.market.FxRateReadPort
import io.premiumspread.domain.ticker.Currency
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CancellationException

/** 확정 손익 정산 Job (#130 dod AC4 (a)~(e)). port·store·executor 는 가짜다. */
@ExtendWith(OutputCaptureExtension::class)
class AccountRealizedPnlJobTest {

    private val clock = MutableClock(NOW)
    private val korea = FakeKoreaFills()
    private val foreign = FakeForeignFills()
    private val store = FakeStore()
    private val fx = FakeFx()
    private val lastSuccess = FakeLastSuccess()

    private fun job(foreignPort: ForeignPerpFillReadPort? = foreign) = AccountRealizedPnlJob(
        korea = korea,
        foreign = foreignPort,
        store = store,
        fx = fx,
        lastSuccess = lastSuccess,
        executor = passThroughExecutor(),
        clock = clock,
        settings = SETTINGS,
    )

    @AfterEach
    fun clearInterrupt() {
        Thread.interrupted()
    }

    // ---- 정상 ----

    @Test
    fun `두 쪽 모두 맞으면 사건 시각 환율로 합계를 채워 확정한다`() {
        val result = job().run()

        assertThat(result).isEqualTo(JobResult.Success)
        val (outcome, nextAt) = store.records.single()
        assertThat(outcome.koreaStatus).isEqualTo(SettlementStatus.CONFIRMED)
        assertThat(outcome.foreignStatus).isEqualTo(SettlementStatus.CONFIRMED)
        assertThat(outcome.korea).isEqualTo(RealizedPnlPolicy.korea(KOREA_FILLS, BigDecimal.ZERO))
        assertThat(outcome.foreign).isEqualTo(RealizedPnlPolicy.foreign(FOREIGN_FILLS, FUNDING, BigDecimal.ZERO))
        assertThat(outcome.fx).isEqualTo(EVENT_FX)
        assertThat(outcome.totalKrw)
            .isEqualByComparingTo(RealizedPnlPolicy.totalKrw(outcome.korea!!, outcome.foreign!!, EVENT_FX.rate))
        assertThat(outcome.failure).isNull()
        assertThat(outcome.decidedAt).isEqualTo(NOW)
        assertThat(outcome.settled).isTrue()
        assertThat(nextAt).isNull()
        assertThat(fx.calls).isZero()
    }

    @Test
    fun `조회 창은 lower 초과 occurredAt 이하이고 환율은 사건 시각 기준 10분 허용으로 찾는다`() {
        job().run()

        assertThat(korea.windows).containsExactly(TARGET.lowerExclusive to TARGET.occurredAt)
        assertThat(foreign.fillWindows).containsExactly(TARGET.lowerExclusive to TARGET.occurredAt)
        assertThat(foreign.fundingWindows).containsExactly(TARGET.lowerExclusive to TARGET.occurredAt)
        assertThat(store.fxQueries).containsExactly(TARGET.occurredAt to Duration.ofMinutes(10))
        assertThat(store.targetQueries).containsExactly(
            listOf(NOW, SETTINGS.settleDelay, SETTINGS.maxAge, SETTINGS.firstEpisodeLookback),
        )
    }

    @Test
    fun `대상이 없으면 조회·기록 없이 Success`() {
        store.target = null

        assertThat(job().run()).isEqualTo(JobResult.Success)
        assertThat(korea.windows).isEmpty()
        assertThat(foreign.fillWindows).isEmpty()
        assertThat(store.records).isEmpty()
    }

    @Test
    fun `Job 은 정산 JobConfig 로 executor 를 거친다`() {
        val executor = mockk<JobExecutor>()
        var jobId: JobId? = null
        every { executor.execute(any(), any()) } answers {
            jobId = firstArg<io.premiumspread.application.common.JobConfig>().jobId
            JobResult.Skipped("locked")
        }
        val job = AccountRealizedPnlJob(korea, foreign, store, fx, lastSuccess, executor, clock, SETTINGS)

        assertThat(job.run()).isEqualTo(JobResult.Skipped("locked"))
        assertThat(jobId).isEqualTo(JobId.ACCOUNT_REALIZED_PNL)
        assertThat(store.records).isEmpty()
    }

    // ---- (a) 조회 실패 → PENDING, 시도 +1, backoff, 다음 회차 확정 ----

    @Test
    fun `(a) 빗썸 조회 실패면 빗썸 PENDING·KOREA_READ·next = now + 5분 이고 Job 은 실패가 아니며 다음 실행에 확정된다`() {
        korea.failure = AccountReadException(AccountReadFailureCategory.BITHUMB_AUTH, "auth")

        assertThat(job().run()).isEqualTo(JobResult.Success)

        val (outcome, nextAt) = store.records.single()
        assertThat(outcome.koreaStatus).isEqualTo(SettlementStatus.PENDING)
        assertThat(outcome.korea).isNull()
        assertThat(outcome.failure).isEqualTo(SettlementFailure.KOREA_READ)
        assertThat(outcome.settled).isFalse()
        assertThat(outcome.totalKrw).isNull()
        assertThat(outcome.fx).isNull()
        // 다른 쪽은 그대로 시도해 확정 값을 남긴다
        assertThat(outcome.foreignStatus).isEqualTo(SettlementStatus.CONFIRMED)
        assertThat(outcome.foreign).isNotNull()
        assertThat(nextAt).isEqualTo(NOW.plus(Duration.ofMinutes(5)))
        assertThat(store.attempts).isEqualTo(1)
        assertThat(store.settledSeq).isNull()

        korea.failure = null
        clock.advance(Duration.ofMinutes(5))
        assertThat(job().run()).isEqualTo(JobResult.Success)

        val (second, secondNext) = store.records.last()
        assertThat(second.koreaStatus).isEqualTo(SettlementStatus.CONFIRMED)
        assertThat(second.foreignStatus).isEqualTo(SettlementStatus.CONFIRMED)
        assertThat(second.failure).isNull()
        assertThat(secondNext).isNull()
        assertThat(store.settledSeq).isEqualTo(TARGET.seq)
    }

    @Test
    fun `(a) 바이낸스 조회 실패면 바이낸스 PENDING·FOREIGN_READ 이고 빗썸 확정 값은 남는다`() {
        foreign.fundingFailure = AccountReadException(AccountReadFailureCategory.NETWORK, "down")

        assertThat(job().run()).isEqualTo(JobResult.Success)

        val (outcome, nextAt) = store.records.single()
        assertThat(outcome.foreignStatus).isEqualTo(SettlementStatus.PENDING)
        assertThat(outcome.foreign).isNull()
        assertThat(outcome.koreaStatus).isEqualTo(SettlementStatus.CONFIRMED)
        assertThat(outcome.korea).isNotNull()
        assertThat(outcome.failure).isEqualTo(SettlementFailure.FOREIGN_READ)
        assertThat(outcome.totalKrw).isNull()
        assertThat(nextAt).isEqualTo(NOW.plus(Duration.ofMinutes(5)))
        assertThat(store.settledSeq).isNull()

        foreign.fundingFailure = null
        clock.advance(FIRST_BACKOFF)
        job().run()
        assertThat(store.records.last().first.foreignStatus).isEqualTo(SettlementStatus.CONFIRMED)
        assertThat(store.settledSeq).isEqualTo(TARGET.seq)
    }

    @ParameterizedTest(name = "attempts {0} → +{1}분")
    @CsvSource("0, 5", "1, 10", "2, 20", "6, 320", "7, 360", "30, 360", "2147483647, 360")
    fun `(a) next 는 now + 5분 × 2^(시도−1) 이고 6시간이 상한이다`(attemptsBefore: Int, minutes: Long) {
        store.target = TARGET.copy(attempts = attemptsBefore)
        korea.failure = AccountReadException(AccountReadFailureCategory.NETWORK, "down")

        job().run()

        assertThat(store.records.single().second).isEqualTo(NOW.plus(Duration.ofMinutes(minutes)))
    }

    @Test
    fun `분류되지 않은 조회 예외도 그 쪽 READ 사유로 미확정이다`() {
        korea.failure = IllegalStateException("parse")
        foreign.fillsFailure = IllegalArgumentException("parse")

        assertThat(job().run()).isEqualTo(JobResult.Success)
        val outcome = store.records.single().first
        assertThat(outcome.koreaStatus).isEqualTo(SettlementStatus.PENDING)
        assertThat(outcome.foreignStatus).isEqualTo(SettlementStatus.PENDING)
        assertThat(outcome.failure).isEqualTo(SettlementFailure.KOREA_READ)
    }

    @Test
    fun `조회 한도 초과는 WINDOW_LIMIT 이다`() {
        foreign.fillsFailure = FillHistoryWindowLimitException("limit")

        job().run()
        assertThat(store.records.last().first.failure).isEqualTo(SettlementFailure.WINDOW_LIMIT)
        assertThat(store.records.last().first.foreignStatus).isEqualTo(SettlementStatus.PENDING)

        foreign.fillsFailure = null
        korea.failure = FillHistoryWindowLimitException("limit")
        clock.advance(FIRST_BACKOFF)
        job().run()
        assertThat(store.records.last().first.failure).isEqualTo(SettlementFailure.WINDOW_LIMIT)
        assertThat(store.records.last().first.koreaStatus).isEqualTo(SettlementStatus.PENDING)
    }

    @Test
    fun `감싸진 조회 한도 초과도 cause 체인에서 찾아 WINDOW_LIMIT 이다`() {
        korea.failure = IllegalStateException("outer", RuntimeException("mid", FillHistoryWindowLimitException("limit")))
        foreign.fundingFailure = AccountReadException(
            AccountReadFailureCategory.UNEXPECTED,
            "wrapped",
            FillHistoryWindowLimitException("limit"),
        )

        job().run()

        val outcome = store.records.single().first
        assertThat(outcome.koreaStatus).isEqualTo(SettlementStatus.PENDING)
        assertThat(outcome.foreignStatus).isEqualTo(SettlementStatus.PENDING)
        assertThat(outcome.failure).isEqualTo(SettlementFailure.WINDOW_LIMIT)

        // 바이낸스 쪽만 감싸진 한도 초과면 바이낸스 사유도 WINDOW_LIMIT 이다
        korea.failure = null
        clock.advance(FIRST_BACKOFF)
        job().run()
        assertThat(store.records.last().first.failure).isEqualTo(SettlementFailure.WINDOW_LIMIT)
    }

    @Test
    fun `실패 사유가 둘 이상이면 빗썸 → 바이낸스 순으로 먼저 난 것을 남긴다`() {
        korea.failure = AccountReadException(AccountReadFailureCategory.NETWORK, "down")
        foreign.fills = FOREIGN_FILLS.dropLast(1)

        job().run()

        val outcome = store.records.single().first
        assertThat(outcome.failure).isEqualTo(SettlementFailure.KOREA_READ)
        assertThat(outcome.foreignStatus).isEqualTo(SettlementStatus.PENDING)
        assertThat(foreign.fillWindows).hasSize(1)
    }

    // ---- (b) 수량 불일치 ----

    @Test
    fun `(b) 빗썸 순수량이 사건 수량과 다르면 KOREA_QUANTITY 로 PENDING 이고 맞으면 다음 실행에 확정된다`() {
        korea.fills = KOREA_FILLS.dropLast(1)

        assertThat(job().run()).isEqualTo(JobResult.Success)
        val (outcome, nextAt) = store.records.single()
        assertThat(outcome.koreaStatus).isEqualTo(SettlementStatus.PENDING)
        assertThat(outcome.korea).isNull()
        assertThat(outcome.failure).isEqualTo(SettlementFailure.KOREA_QUANTITY)
        assertThat(nextAt).isEqualTo(NOW.plus(Duration.ofMinutes(5)))
        assertThat(store.settledSeq).isNull()

        korea.fills = KOREA_FILLS
        clock.advance(FIRST_BACKOFF)
        job().run()
        assertThat(store.records.last().first.koreaStatus).isEqualTo(SettlementStatus.CONFIRMED)
        assertThat(store.settledSeq).isEqualTo(TARGET.seq)
    }

    @Test
    fun `(b) 바이낸스 부호 수량 합이 다르면 FOREIGN_QUANTITY 로 PENDING 이고 맞으면 다음 실행에 확정된다`() {
        foreign.fills = FOREIGN_FILLS.dropLast(1)

        job().run()
        val (outcome, nextAt) = store.records.single()
        assertThat(outcome.foreignStatus).isEqualTo(SettlementStatus.PENDING)
        assertThat(outcome.foreign).isNull()
        assertThat(outcome.failure).isEqualTo(SettlementFailure.FOREIGN_QUANTITY)
        assertThat(nextAt).isEqualTo(NOW.plus(Duration.ofMinutes(5)))

        foreign.fills = FOREIGN_FILLS
        clock.advance(FIRST_BACKOFF)
        job().run()
        assertThat(store.records.last().first.foreignStatus).isEqualTo(SettlementStatus.CONFIRMED)
        assertThat(store.settledSeq).isEqualTo(TARGET.seq)
    }

    @Test
    fun `(b) 기대 수량은 사건 정규화 leg 의 수량이다`() {
        // 부분 정리: 빗썸 0.01 남고 바이낸스 -0.01 남는다
        store.target = TARGET.copy(
            legs = PositionLegs(KoreaLeg.of(BigDecimal("0.01"), BigDecimal("100000000")), ForeignLeg.of(BigDecimal("-0.01"), BigDecimal("70000"))),
        )
        korea.fills = KOREA_FILLS.take(1)
        foreign.fills = FOREIGN_FILLS.take(1)

        job().run()

        val outcome = store.records.single().first
        assertThat(outcome.koreaStatus).isEqualTo(SettlementStatus.CONFIRMED)
        assertThat(outcome.foreignStatus).isEqualTo(SettlementStatus.CONFIRMED)
    }

    // ---- (c) MANUAL ----

    @Test
    fun `(c) 목표 사건이 MANUAL_ENV 면 바이낸스는 MANUAL·값과 합계 NULL 이고 빗썸 확정 시 settled_seq 가 오른다`() {
        store.target = TARGET.copy(source = ForeignPerpPositionSource.MANUAL_ENV)

        assertThat(job().run()).isEqualTo(JobResult.Success)

        val (outcome, nextAt) = store.records.single()
        assertThat(outcome.foreignStatus).isEqualTo(SettlementStatus.MANUAL)
        assertThat(outcome.foreign).isNull()
        assertThat(outcome.totalKrw).isNull()
        assertThat(outcome.fx).isNull()
        assertThat(outcome.koreaStatus).isEqualTo(SettlementStatus.CONFIRMED)
        assertThat(outcome.failure).isNull()
        assertThat(outcome.settled).isTrue()
        assertThat(nextAt).isNull()
        assertThat(store.settledSeq).isEqualTo(TARGET.seq)
        assertThat(foreign.fillWindows).isEmpty()
        assertThat(foreign.fundingWindows).isEmpty()
        assertThat(store.fxQueries).isEmpty()
        assertThat(fx.calls).isZero()
    }

    @Test
    fun `(c) MANUAL_ENV 라도 빗썸이 미확정이면 settled_seq 는 그대로다`() {
        store.target = TARGET.copy(source = ForeignPerpPositionSource.MANUAL_ENV)
        korea.fills = KOREA_FILLS.dropLast(1)

        job().run()

        val outcome = store.records.single().first
        assertThat(outcome.foreignStatus).isEqualTo(SettlementStatus.MANUAL)
        assertThat(outcome.failure).isEqualTo(SettlementFailure.KOREA_QUANTITY)
        assertThat(store.settledSeq).isNull()
    }

    @Test
    fun `(c) 바이낸스 체결 port 가 없으면(수동 모드) 바이낸스는 MANUAL 이다`() {
        job(foreignPort = null).run()

        val outcome = store.records.single().first
        assertThat(outcome.foreignStatus).isEqualTo(SettlementStatus.MANUAL)
        assertThat(outcome.foreign).isNull()
        assertThat(outcome.totalKrw).isNull()
        assertThat(store.settledSeq).isEqualTo(TARGET.seq)
    }

    // ---- (d) 환율 대체·신선도 ----

    @Test
    fun `(d) 사건 시각 환율이 없으면 FX port 최신 값과 그 관측 시각을 쓴다`() {
        store.fxAt = null

        job().run()

        val outcome = store.records.single().first
        val expected = AppliedFx(LATEST_RATE, fx.observedAt)
        assertThat(outcome.fx).isEqualTo(expected)
        assertThat(outcome.totalKrw)
            .isEqualByComparingTo(RealizedPnlPolicy.totalKrw(outcome.korea!!, outcome.foreign!!, LATEST_RATE))
        assertThat(outcome.settled).isTrue()
        assertThat(fx.calls).isEqualTo(1)
    }

    @Test
    fun `(d) FX port 값도 수집 공백이 신선도를 넘으면 FX 사유로 PENDING 이고 두 쪽 값은 남고 합계는 NULL 이다`() {
        store.fxAt = null
        lastSuccess.fxIngestion = NOW.minus(Duration.ofMinutes(31))

        assertThat(job().run()).isEqualTo(JobResult.Success)

        val (outcome, nextAt) = store.records.single()
        assertThat(outcome.failure).isEqualTo(SettlementFailure.FX)
        assertThat(outcome.settled).isFalse()
        assertThat(outcome.fx).isNull()
        assertThat(outcome.totalKrw).isNull()
        assertThat(outcome.korea).isNotNull()
        assertThat(outcome.foreign).isNotNull()
        assertThat(outcome.koreaStatus).isEqualTo(SettlementStatus.CONFIRMED)
        assertThat(outcome.foreignStatus).isEqualTo(SettlementStatus.PENDING)
        assertThat(nextAt).isEqualTo(NOW.plus(Duration.ofMinutes(5)))
        assertThat(store.settledSeq).isNull()
    }

    @Test
    fun `(d) 신선도 경계 30분은 신선하다`() {
        store.fxAt = null
        lastSuccess.fxIngestion = NOW.minus(Duration.ofMinutes(30))

        job().run()

        assertThat(store.records.single().first.failure).isNull()
    }

    @Test
    fun `(d) FX port 에 값이 없거나 예외면 FX 사유다`() {
        store.fxAt = null
        fx.missing = true
        job().run()
        assertThat(store.records.last().first.failure).isEqualTo(SettlementFailure.FX)

        fx.missing = false
        fx.failure = IllegalStateException("redis")
        clock.advance(FIRST_BACKOFF)
        job().run()
        assertThat(store.records.last().first.failure).isEqualTo(SettlementFailure.FX)
    }

    @Test
    fun `(d) 수집 성공 기록이 없으면 첫 실행 시각을 기준으로 잰다`() {
        store.fxAt = null
        lastSuccess.fxIngestion = null
        val job = job()

        job.run()
        assertThat(store.records.last().first.failure).isNull()

        store.settledSeq = null
        clock.advance(Duration.ofMinutes(31))
        job.run()
        assertThat(store.records.last().first.failure).isEqualTo(SettlementFailure.FX)
    }

    @Test
    fun `(d) 한쪽이라도 미확정이면 환율을 찾지 않는다`() {
        korea.fills = KOREA_FILLS.dropLast(1)

        job().run()

        assertThat(store.fxQueries).isEmpty()
        assertThat(fx.calls).isZero()
    }

    // ---- (e) 취소 ----

    @Test
    fun `(e) 빗썸 조회의 취소 예외는 삼키지 않고 interrupt 를 되살린다`() {
        korea.failure = CancellationException("cancelled")

        assertThatThrownBy { job().run() }.isInstanceOf(CancellationException::class.java)
        assertThat(Thread.currentThread().isInterrupted).isTrue()
        assertThat(store.records).isEmpty()
    }

    @Test
    fun `(e) 바이낸스 조회가 감싼 InterruptedException 도 취소로 다시 던진다`() {
        foreign.fillsFailure = AccountReadException(
            AccountReadFailureCategory.NETWORK,
            "wrapped",
            InterruptedException("interrupted"),
        )

        assertThatThrownBy { job().run() }.isInstanceOf(AccountReadException::class.java)
        assertThat(Thread.currentThread().isInterrupted).isTrue()
        assertThat(store.records).isEmpty()
    }

    @Test
    fun `(e) FX 조회의 취소 예외도 다시 던진다`() {
        store.fxAt = null
        fx.failure = CancellationException("cancelled")

        assertThatThrownBy { job().run() }.isInstanceOf(CancellationException::class.java)
        assertThat(Thread.currentThread().isInterrupted).isTrue()
        assertThat(store.records).isEmpty()
    }

    @Test
    fun `(e) 조회 중 interrupt 가 걸렸으면 기록하지 않는다`() {
        korea.onRead = { Thread.currentThread().interrupt() }

        assertThat(job().run()).isInstanceOf(JobResult.Skipped::class.java)
        assertThat(store.records).isEmpty()
    }

    @Test
    fun `(e) 취소된 시도도 조회 전에 시도 횟수와 next 를 남겨 backoff 동안 대상에서 빠진다`() {
        korea.onRead = {
            assertThat(store.attempts).isEqualTo(1)
            assertThat(store.nextAt).isEqualTo(NOW.plus(FIRST_BACKOFF))
        }
        korea.failure = CancellationException("timeout")

        assertThatThrownBy { job().run() }.isInstanceOf(CancellationException::class.java)

        assertThat(store.records).isEmpty()
        assertThat(store.attempts).isEqualTo(1)
        assertThat(store.nextAt).isEqualTo(NOW.plus(FIRST_BACKOFF))
        assertThat(store.attemptTargetSeq).isEqualTo(TARGET.seq)
        assertThat(store.findTarget(NOW.plus(Duration.ofMinutes(1)), SETTINGS.settleDelay, SETTINGS.maxAge, SETTINGS.firstEpisodeLookback))
            .isNull()
    }

    @Test
    fun `시도 시작이 단조 조건에 막히면 조회·기록 없이 Success`() {
        store.beginReturns = false

        assertThat(job().run()).isEqualTo(JobResult.Success)
        assertThat(korea.windows).isEmpty()
        assertThat(foreign.fillWindows).isEmpty()
        assertThat(store.records).isEmpty()
    }

    // ---- store ----

    @Test
    fun `store 예외만 JobResult Failure 다`() {
        store.findTargetFailure = IllegalStateException("db")
        assertThat(job().run()).isInstanceOf(JobResult.Failure::class.java)

        store.findTargetFailure = null
        store.beginAttemptFailure = IllegalStateException("db")
        assertThat(job().run()).isInstanceOf(JobResult.Failure::class.java)
        assertThat(korea.windows).isEmpty()

        store.beginAttemptFailure = null
        store.fxAtFailure = IllegalStateException("db")
        assertThat(job().run()).isInstanceOf(JobResult.Failure::class.java)

        store.fxAtFailure = null
        store.recordFailure = IllegalStateException("db")
        // 앞 시도가 남긴 backoff 를 지난다
        clock.advance(FIRST_BACKOFF)
        assertThat(job().run()).isInstanceOf(JobResult.Failure::class.java)
    }

    @Test
    fun `단조 조건에 막혀 기록되지 않아도 실패가 아니다`() {
        store.recordReturns = false

        assertThat(job().run()).isEqualTo(JobResult.Success)
    }

    @Test
    fun `로그에는 건 id·seq·상태·사유만 있고 금액은 없다`(output: CapturedOutput) {
        korea.fills = KOREA_FILLS.dropLast(1)
        job().run()
        korea.fills = KOREA_FILLS
        clock.advance(FIRST_BACKOFF)
        job().run()

        val text = output.all
        assertThat(text).contains("episodeId=7", "seq=3", "KOREA_QUANTITY")
        val outcome = store.records.last().first
        listOf(
            outcome.korea!!.realizedKrw,
            outcome.foreign!!.realizedPnlUsdt,
            outcome.foreign!!.commissionUsdt,
            outcome.foreign!!.fundingUsdt,
            outcome.totalKrw!!,
            EVENT_FX.rate,
        ).forEach { assertThat(text).doesNotContain(it.toPlainString()) }
    }

    // ---- fakes ----

    private fun passThroughExecutor(): JobExecutor {
        val executor = mockk<JobExecutor>()
        every { executor.execute(any(), any()) } answers { secondArg<() -> JobResult>().invoke() }
        return executor
    }

    class MutableClock(private var now: Instant) : Clock() {
        fun advance(duration: Duration) {
            now = now.plus(duration)
        }

        override fun instant(): Instant = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
    }

    class FakeKoreaFills : KoreaSpotFillReadPort {
        var fills: List<KoreaSpotFill> = KOREA_FILLS
        var failure: Exception? = null
        var onRead: () -> Unit = {}
        val windows = mutableListOf<Pair<Instant, Instant>>()

        override fun read(fromExclusive: Instant, toInclusive: Instant): List<KoreaSpotFill> {
            windows += fromExclusive to toInclusive
            onRead()
            failure?.let { throw it }
            return fills
        }
    }

    class FakeForeignFills : ForeignPerpFillReadPort {
        var fills: List<ForeignPerpFill> = FOREIGN_FILLS
        var funding: List<ForeignFunding> = FUNDING
        var fillsFailure: Exception? = null
        var fundingFailure: Exception? = null
        val fillWindows = mutableListOf<Pair<Instant, Instant>>()
        val fundingWindows = mutableListOf<Pair<Instant, Instant>>()

        override fun fills(fromExclusive: Instant, toInclusive: Instant): List<ForeignPerpFill> {
            fillWindows += fromExclusive to toInclusive
            fillsFailure?.let { throw it }
            return fills
        }

        override fun funding(fromExclusive: Instant, toInclusive: Instant): List<ForeignFunding> {
            fundingWindows += fromExclusive to toInclusive
            fundingFailure?.let { throw it }
            return funding
        }
    }

    /**
     * 실제 store 처럼 시도 시작이 attempts+1·next_at·목표 seq 를 쓰고, next_at 이 지나기 전에는 같은 목표를 돌려주지 않는다.
     * settled 면 settled_seq 를 올리고 대상에서 빠진다. 미확정 기록은 attempts 를 다시 세지 않는다.
     */
    class FakeStore : AccountRealizedPnlStore {
        var target: SettlementTarget? = TARGET
        var fxAt: AppliedFx? = EVENT_FX
        var settledSeq: Int? = null
        var attempts = 0
        var nextAt: Instant? = null
        var attemptTargetSeq: Int? = null
        var recordReturns = true
        var beginReturns = true
        var beginAttemptFailure: Exception? = null
        var findTargetFailure: Exception? = null
        var fxAtFailure: Exception? = null
        var recordFailure: Exception? = null
        val records = mutableListOf<Pair<SettlementOutcome, Instant?>>()
        val targetQueries = mutableListOf<List<Any>>()
        val fxQueries = mutableListOf<Pair<Instant, Duration>>()

        override fun findTarget(now: Instant, settleDelay: Duration, maxAge: Duration, firstLookback: Duration): SettlementTarget? {
            targetQueries += listOf(now, settleDelay, maxAge, firstLookback)
            findTargetFailure?.let { throw it }
            val backedOff = nextAt?.let { it > now } == true && (target?.seq ?: 0) <= (attemptTargetSeq ?: 0)
            return target?.takeIf { (settledSeq == null || it.seq > settledSeq!!) && !backedOff }
        }

        override fun beginAttempt(target: SettlementTarget, nextAttemptAt: Instant): Boolean {
            beginAttemptFailure?.let { throw it }
            if (!beginReturns) return false
            attempts += 1
            nextAt = nextAttemptAt
            attemptTargetSeq = target.seq
            this.target = this.target?.copy(attempts = attempts)
            return true
        }

        override fun findFxAt(at: Instant, tolerance: Duration): AppliedFx? {
            fxQueries += at to tolerance
            fxAtFailure?.let { throw it }
            return fxAt
        }

        override fun record(outcome: SettlementOutcome, nextAttemptAt: Instant?): Boolean {
            recordFailure?.let { throw it }
            records += outcome to nextAttemptAt
            if (!recordReturns) return false
            if (outcome.settled) {
                settledSeq = outcome.target.seq
                attempts = 0
                nextAt = null
            } else {
                nextAt = nextAttemptAt
            }
            return true
        }
    }

    class FakeFx : FxRateReadPort {
        var missing = false
        var failure: Exception? = null
        var calls = 0
        val observedAt: Instant = NOW.minus(Duration.ofMinutes(2))

        override fun findLatest(base: Currency, quote: Currency): ExchangeRateSnapshot? {
            check(base == Currency.USD && quote == Currency.KRW) { "unexpected pair $base/$quote" }
            calls += 1
            failure?.let { throw it }
            return if (missing) null else ExchangeRateSnapshot("USD", "KRW", LATEST_RATE, observedAt)
        }
    }

    class FakeLastSuccess : JobLastSuccessReadPort {
        var fxIngestion: Instant? = NOW.minus(Duration.ofMinutes(10))

        override fun findLastSuccess(jobId: JobId): Instant? {
            check(jobId == JobId.FX_INGESTION) { "unexpected job $jobId" }
            return fxIngestion
        }
    }

    companion object {
        val OCCURRED_AT: Instant = Instant.parse("2026-10-20T03:00:00Z")
        val NOW: Instant = OCCURRED_AT.plus(Duration.ofMinutes(10))
        val FIRST_BACKOFF: Duration = Duration.ofMinutes(5)
        val SETTINGS = AccountRealizedPnlJob.Settings(
            settleDelay = Duration.ofMinutes(2),
            firstEpisodeLookback = Duration.ofHours(1),
            maxAge = Duration.ofDays(90),
        )

        val TARGET = SettlementTarget(
            episodeId = 7,
            seq = 3,
            occurredAt = OCCURRED_AT,
            legs = PositionLegs(KoreaLeg.EMPTY, ForeignLeg.FLAT),
            source = ForeignPerpPositionSource.EXCHANGE_API,
            lowerExclusive = OCCURRED_AT.minus(Duration.ofDays(1)),
            attempts = 0,
        )

        val EVENT_FX = AppliedFx(BigDecimal("1391.37"), OCCURRED_AT)
        val LATEST_RATE = BigDecimal("1402.73")

        val KOREA_FILLS = listOf(
            KoreaSpotFill(
                "o1:t1",
                FillSide.BUY,
                BigDecimal("0.01"),
                BigDecimal("1000001"),
                BigDecimal("401"),
                OCCURRED_AT.minus(Duration.ofHours(5)),
            ),
            KoreaSpotFill(
                "o2:t1",
                FillSide.SELL,
                BigDecimal("0.01"),
                BigDecimal("1012347"),
                BigDecimal("407"),
                OCCURRED_AT.minus(Duration.ofSeconds(30)),
            ),
        )

        val FOREIGN_FILLS = listOf(
            ForeignPerpFill(11, BigDecimal("-0.01"), BigDecimal.ZERO, BigDecimal("0.3517"), OCCURRED_AT.minus(Duration.ofHours(5))),
            ForeignPerpFill(12, BigDecimal("0.01"), BigDecimal("-8.6123"), BigDecimal("0.3589"), OCCURRED_AT.minus(Duration.ofSeconds(20))),
        )

        val FUNDING = listOf(ForeignFunding(21, BigDecimal("0.4219"), OCCURRED_AT.minus(Duration.ofHours(1))))
    }
}
