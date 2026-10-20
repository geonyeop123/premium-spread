package io.premiumspread.application.job.accountpnl

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.premiumspread.application.common.DefaultJobConfigProvider
import io.premiumspread.application.common.JobConfig
import io.premiumspread.application.common.JobExecutor
import io.premiumspread.application.common.JobResult
import io.premiumspread.domain.accountpnl.AccountPnlAlertStore
import io.premiumspread.domain.accountpnl.AccountPnlMail
import io.premiumspread.domain.accountpnl.AccountPnlMailSender
import io.premiumspread.domain.accountpnl.AccountPnlMonitorState
import io.premiumspread.domain.accountpnl.AccountReadException
import io.premiumspread.domain.accountpnl.AccountReadFailureCategory
import io.premiumspread.domain.accountpnl.ClaimedAccountPnlMail
import io.premiumspread.domain.accountpnl.ForeignPerpAccountReadPort
import io.premiumspread.domain.accountpnl.ForeignPerpAccountSnapshot
import io.premiumspread.domain.accountpnl.KoreaSpotAccountReadPort
import io.premiumspread.domain.accountpnl.KoreaSpotAccountSnapshot
import io.premiumspread.domain.accountpnl.OutageState
import io.premiumspread.domain.accountpnl.PnlAlertState
import io.premiumspread.domain.accountpnl.PriceLevel
import io.premiumspread.domain.exchangerate.ExchangeRateSnapshot
import io.premiumspread.domain.job.JobId
import io.premiumspread.domain.job.JobLastSuccessReadPort
import io.premiumspread.domain.market.FxRateReadPort
import io.premiumspread.domain.ticker.Currency
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/**
 * 모니터링 Job 의 조합 계약 (dod AC5, design §3 신선도·§4).
 *
 * port 는 모두 fake 다. store fake 는 낙관적 version·claim token fencing·시도 횟수를 실제 계약대로 흉내 낸다.
 * 기본 시나리오는 순수익률 약 1.96% (빗썸 0.01 BTC 평단 1억 → 매수호가 1.02억, 바이낸스 진입가 = 매도호가, 수수료 0).
 */
class AccountPnlMonitorJobTest {
    private val t0 = Instant.parse("2026-10-20T03:00:00Z")
    private val clock = MutableClock(t0)
    private val executor = mockk<JobExecutor>()

    private lateinit var korea: FakeKorea
    private lateinit var foreign: FakeForeign
    private lateinit var fx: FakeFx
    private lateinit var lastSuccess: FakeLastSuccess
    private lateinit var store: FakeStore
    private lateinit var sender: FakeSender
    private lateinit var job: AccountPnlMonitorJob

    @BeforeEach
    fun setUp() {
        every { executor.execute(any(), any()) } answers { secondArg<() -> JobResult>().invoke() }
        korea = FakeKorea(clock)
        foreign = FakeForeign(clock)
        fx = FakeFx(clock)
        lastSuccess = FakeLastSuccess(clock)
        store = FakeStore()
        sender = FakeSender()
        job = newJob()
    }

    private fun newJob() = AccountPnlMonitorJob(korea, foreign, fx, lastSuccess, store, sender, executor, clock)

    // ---- 정상 회차 ----

    @Test
    fun `1퍼센트 이상이면 메일 1건을 저장하고 보낸다`() {
        val result = job.run()

        assertThat(result).isEqualTo(JobResult.Success)
        assertThat(store.rows).hasSize(1)
        assertThat(store.rows.single().status).isEqualTo(Status.SENT)
        assertThat(sender.sent).hasSize(1)
        assertThat(sender.sent.single().subject).startsWith("[김프 알림] 순수익률 +1.96%")
        assertThat(store.state.alert).isEqualTo(PnlAlertState(true, store.state.alert.positionFingerprint, t0))
        assertThat(store.state.outage.lastSuccessAt).isEqualTo(t0)
        assertThat(store.state.version).isEqualTo(1)
    }

    @Test
    fun `1퍼센트 미만이면 메일을 만들지 않고 성공 회차로 저장한다`() {
        korea.bid = BigDecimal("100500000") // 0.49%

        assertThat(job.run()).isEqualTo(JobResult.Success)

        assertThat(store.rows).isEmpty()
        assertThat(sender.sent).isEmpty()
        assertThat(store.state.alert).isEqualTo(PnlAlertState.INITIAL)
        assertThat(store.state.outage.lastSuccessAt).isEqualTo(t0)
    }

    @Test
    fun `typed JobConfig 의 lock key lease timeout 을 쓴다`() {
        assertThat(DefaultJobConfigProvider.get(JobId.ACCOUNT_PNL_MONITOR)).isEqualTo(
            JobConfig(JobId.ACCOUNT_PNL_MONITOR, "lock:account-pnl:monitor", Duration.ofSeconds(50), Duration.ofSeconds(40)),
        )

        job.run()

        verify(exactly = 1) { executor.execute(DefaultJobConfigProvider.get(JobId.ACCOUNT_PNL_MONITOR), any()) }
    }

    @Test
    fun `stale claim 복구를 load 보다 먼저 5분 기준으로 실행하고 인스턴스당 같은 owner 로 claim 한다`() {
        job.run()
        clock.advance(Duration.ofMinutes(1))
        job.run()

        assertThat(store.calls.take(2)).containsExactly("recoverStale@$t0/PT5M", "load")
        assertThat(store.owners).hasSize(2)
        assertThat(store.owners.toSet()).hasSize(1)
        assertThat(store.owners.first()).startsWith("account-pnl-monitor:")
        assertThat(store.claimLimits).containsOnly(10)
    }

    // ---- 시세 신선도 ----

    @Test
    fun `호가가 10초 넘게 오래되면 계산 실패로 보고 수익 메일을 만들지 않는다`() {
        givenLastCalculationSucceededLongAgo()
        korea.age = Duration.ofMillis(10_001)
        foreign.age = Duration.ofMillis(10_001)

        assertThat(job.run()).isEqualTo(JobResult.Success)

        assertNoProfitMail()
        assertOutageMail("시세 지연")
        assertThat(store.state.alert).isEqualTo(PnlAlertState.INITIAL)
    }

    @Test
    fun `바이낸스 호가만 10초 넘게 오래돼도 STALE_MARKET_DATA 다`() {
        givenLastCalculationSucceededLongAgo()
        korea.age = Duration.ofSeconds(6)
        foreign.age = Duration.ofMillis(10_001)

        assertThat(job.run()).isEqualTo(JobResult.Success)

        assertNoProfitMail()
        assertOutageMail("시세 지연")
    }

    @Test
    fun `호가 나이가 정확히 10초면 계산한다`() {
        korea.age = Duration.ofSeconds(10)
        foreign.age = Duration.ofSeconds(10)

        job.run()

        assertProfitMailSent()
    }

    @Test
    fun `미래 시각 호가는 신선으로 본다`() {
        korea.age = Duration.ofSeconds(-30)
        foreign.age = Duration.ofSeconds(-30)

        assertThat(job.run()).isEqualTo(JobResult.Success)

        assertProfitMailSent()
    }

    @Test
    fun `두 호가 시각 차이 5초는 계산하고 5초 1밀리초는 STALE_MARKET_DATA`() {
        korea.age = Duration.ZERO
        foreign.age = Duration.ofSeconds(5)
        job.run()
        assertProfitMailSent()

        setUp()
        givenLastCalculationSucceededLongAgo()
        korea.age = Duration.ofMillis(5_001)
        foreign.age = Duration.ZERO
        assertThat(job.run()).isEqualTo(JobResult.Success)
        assertNoProfitMail()
        assertOutageMail("시세 지연")
    }

    // ---- 환율 신선도 ----

    @Test
    fun `환율 수집 마지막 성공이 2시간을 넘으면 STALE_FX`() {
        givenLastCalculationSucceededLongAgo()
        lastSuccess.fxIngestion = { clock.instant().minus(Duration.ofHours(2)).minusMillis(1) }

        assertThat(job.run()).isEqualTo(JobResult.Success)

        assertNoProfitMail()
        assertOutageMail("환율 수집 지연")
    }

    @Test
    fun `환율 수집 마지막 성공이 정확히 2시간이면 계산한다`() {
        lastSuccess.fxIngestion = { clock.instant().minus(Duration.ofHours(2)) }

        job.run()

        assertProfitMailSent()
    }

    @Test
    fun `환율 값이 오래돼도 수집 성공이 2시간 이내면 계산한다`() {
        fx.observedAt = { clock.instant().minus(Duration.ofDays(3)) }
        lastSuccess.fxIngestion = { clock.instant().minus(Duration.ofMinutes(10)) }

        assertThat(job.run()).isEqualTo(JobResult.Success)

        assertProfitMailSent()
        assertThat(sender.sent.single().body).contains("환율 관측: 2026-10-17")
    }

    @Test
    fun `환율 값이 없으면 STALE_FX`() {
        givenLastCalculationSucceededLongAgo()
        fx.missing = true

        assertThat(job.run()).isEqualTo(JobResult.Success)

        assertNoProfitMail()
        assertOutageMail("환율 수집 지연")
    }

    @Test
    fun `FX 수집 성공 기록이 없으면 Job 첫 실행 시각부터 2시간을 잰다`() {
        lastSuccess.fxIngestion = { null }
        korea.bid = BigDecimal("100500000") // 수익 메일 없이 성공 여부만 본다

        job.run()
        assertThat(store.state.outage.lastSuccessAt).isEqualTo(t0)

        clock.advance(Duration.ofHours(2))
        job.run()
        assertThat(store.state.outage.lastSuccessAt).describedAs("첫 실행 후 정확히 2시간은 신선").isEqualTo(clock.instant())

        val lastComputed = clock.instant()
        clock.advance(Duration.ofMillis(1))
        job.run()
        assertThat(store.state.outage.lastSuccessAt).describedAs("2시간 1밀리초는 STALE_FX").isEqualTo(lastComputed)

        clock.advance(Duration.ofMinutes(6))
        job.run()
        assertOutageMail("환율 수집 지연")
    }

    // ---- 실패 범주 ----

    @Test
    fun `조회 실패는 Job 실패가 아니다`() {
        givenLastCalculationSucceededLongAgo()
        val before = store.state.alert
        korea.failure = AccountReadException(AccountReadFailureCategory.BITHUMB_AUTH, "bithumb auth failed")

        assertThat(job.run()).isEqualTo(JobResult.Success)

        assertNoProfitMail()
        assertOutageMail("빗썸 인증 실패")
        assertThat(store.state.alert).describedAs("실패 회차는 수익 알림 상태를 바꾸지 않는다").isEqualTo(before)
    }

    @Test
    fun `분류되지 않은 예외는 UNEXPECTED 로 중단 정책에 들어간다`() {
        givenLastCalculationSucceededLongAgo()
        foreign.failure = IllegalStateException("unparsable payload")

        assertThat(job.run()).isEqualTo(JobResult.Success)

        assertNoProfitMail()
        assertOutageMail("기타 오류")
    }

    @Test
    fun `호가 깊이 부족은 INSUFFICIENT_DEPTH 로 중단 정책에 들어간다`() {
        givenLastCalculationSucceededLongAgo()
        korea.bidDepth = BigDecimal("0.005")

        assertThat(job.run()).isEqualTo(JobResult.Success)

        assertNoProfitMail()
        assertOutageMail("호가 깊이 부족")
    }

    @Test
    fun `실패가 5분을 넘지 않으면 중단 메일 없이 실패만 기록한다`() {
        job.run()
        sender.sent.clear()
        clock.advance(Duration.ofMinutes(5))
        korea.failure = AccountReadException(AccountReadFailureCategory.NETWORK, "timeout")

        assertThat(job.run()).isEqualTo(JobResult.Success)

        assertThat(sender.sent).isEmpty()
        assertThat(store.state.outage.lastSuccessAt).isEqualTo(t0)
    }

    @Test
    fun `포지션이 없으면 성공 회차로 보고 중단 후라면 복구 메일을 보낸다`() {
        store.state = AccountPnlMonitorState(
            PnlAlertState(true, "old", t0.minusSeconds(600)),
            OutageState(t0.minus(Duration.ofMinutes(10)), t0.minus(Duration.ofHours(1)), true),
            3,
        )
        foreign.signedQuantity = BigDecimal.ZERO

        assertThat(job.run()).isEqualTo(JobResult.Success)

        assertThat(sender.sent.map { it.subject }).containsExactly("[김프 알림] 모니터링 복구")
        assertThat(store.state.alert).isEqualTo(PnlAlertState.INITIAL)
        assertThat(store.state.outage).isEqualTo(OutageState(t0, t0.minus(Duration.ofHours(1)), false))
    }

    // ---- 발송 ----

    @Test
    fun `SMTP 실패 메일은 PENDING 으로 남고 다음 회차에 다시 보낸다`() {
        sender.failing = true

        assertThat(job.run()).isEqualTo(JobResult.Success)

        val row = store.rows.single()
        assertThat(row.status).isEqualTo(Status.PENDING)
        assertThat(row.attempts).isEqualTo(1)

        sender.failing = false
        clock.advance(Duration.ofMinutes(1))
        assertThat(job.run()).isEqualTo(JobResult.Success)

        assertThat(store.rows).hasSize(1)
        assertThat(store.rows.single().status).isEqualTo(Status.SENT)
        assertThat(sender.sent.single().eventKey).isEqualTo(row.mail.eventKey)
    }

    @Test
    fun `SMTP 실패가 재발송 타이머를 바꾸지 않는다`() {
        sender.failing = true
        job.run()
        assertThat(store.state.alert.lastSentAt).isEqualTo(t0)

        sender.failing = false
        clock.advance(Duration.ofMinutes(1))
        job.run() // 첫 메일이 이 회차에 나간다
        assertThat(store.state.alert.lastSentAt).describedAs("늦게 나간 발송이 타이머를 밀지 않는다").isEqualTo(t0)

        clock.set(t0.plus(Duration.ofMinutes(59)))
        job.run()
        assertThat(store.rows).hasSize(1)

        clock.set(t0.plus(Duration.ofMinutes(60)))
        job.run()
        assertThat(store.rows).hasSize(2)
        assertThat(store.state.alert.lastSentAt).isEqualTo(t0.plus(Duration.ofMinutes(60)))
        assertThat(sender.sent).hasSize(2)
    }

    @Test
    fun `한 메일의 SMTP 실패가 같은 회차의 다음 메일 발송을 막지 않는다`() {
        store.state = AccountPnlMonitorState(
            PnlAlertState.INITIAL,
            OutageState(t0.minus(Duration.ofMinutes(10)), t0.minus(Duration.ofHours(1)), true),
            1,
        )
        sender.failSubjects = { it.startsWith("[김프 알림] 순수익률") }

        assertThat(job.run()).isEqualTo(JobResult.Success)

        assertThat(store.rows.map { it.status }).containsExactly(Status.PENDING, Status.SENT)
        assertThat(sender.sent.map { it.subject }).containsExactly("[김프 알림] 모니터링 복구")
    }

    @Test
    fun `fencing 으로 mark 가 거부돼도 Job 실패가 아니다`() {
        store.fenceMarks = true

        assertThat(job.run()).isEqualTo(JobResult.Success)
        assertThat(sender.sent).hasSize(1)
    }

    // ---- 저장소 ----

    @Test
    fun `상태 version 충돌이면 메일을 보내지 않고 Skipped`() {
        store.concurrentWriteBeforeSave = true

        val result = job.run()

        assertThat(result).isEqualTo(JobResult.Skipped("state-version-conflict"))
        assertThat(sender.sent).isEmpty()
        assertThat(store.rows).isEmpty()
        assertThat(store.calls).doesNotContain("claim")
    }

    @Test
    fun `store 예외는 Failure`() {
        listOf("recoverStale", "load", "save", "claim", "markSent", "markAttemptFailed").forEach { operation ->
            setUp()
            if (operation == "markAttemptFailed") sender.failing = true
            store.failOn = operation

            val result = job.run()

            assertThat(result).describedAs(operation).isInstanceOf(JobResult.Failure::class.java)
            assertThat((result as JobResult.Failure).exception).describedAs(operation).hasMessage("store down: $operation")
        }
    }

    @Test
    fun `메일 발송 예외는 store 예외가 아니다`() {
        sender.failing = true

        assertThat(job.run()).isEqualTo(JobResult.Success)
    }

    @Test
    @ExtendWith(OutputCaptureExtension::class)
    fun `발송 실패 로그는 예외와 cause 타입만 남기고 메시지·주소는 남기지 않는다`(output: CapturedOutput) {
        sender.failure = IllegalStateException(
            "send to owner@example.test failed",
            IllegalArgumentException("deliveryId rejected for owner@example.test"),
        )

        job.run()

        assertThat(output.all).contains("error=IllegalStateException", "cause=IllegalArgumentException")
            .doesNotContain("owner@example.test")
    }

    @Test
    fun `JobId tag 는 다른 작업과 같은 snake_case 다`() {
        assertThat(JobId.ACCOUNT_PNL_MONITOR.tag).isEqualTo("account_pnl_monitor")
    }

    @Test
    fun `claim 은 발송 직전 시각으로 한다`() {
        korea.onRead = { clock.advance(Duration.ofSeconds(3)) }

        job.run()

        assertThat(store.claimTimes).containsExactly(t0.plusSeconds(3))
    }

    // ---- 재시작·취소 ----

    @Test
    fun `재시작한 Job 의 첫 실패 회차가 저장된 마지막 성공 기준으로 즉시 중단 메일을 만든다`() {
        store.state = AccountPnlMonitorState(
            PnlAlertState.INITIAL,
            OutageState(t0.minus(Duration.ofHours(3)), t0.minus(Duration.ofDays(1)), false),
            42,
        )
        korea.failure = AccountReadException(AccountReadFailureCategory.NETWORK, "timeout")
        val restarted = newJob()

        assertThat(restarted.run()).isEqualTo(JobResult.Success)

        assertOutageMail("네트워크")
        assertThat(store.rows.single().mail.body).contains("마지막 성공: 2026-10-20 09:00:00")
        assertThat(sender.sent).hasSize(1)
        assertThat(store.state.outage.outageNotified).isTrue()
    }

    @Test
    fun `관측 중 인터럽트 예외는 실패 범주로 삼키지 않고 저장 없이 다시 던진다`() {
        korea.failure = IllegalStateException("wrapped", InterruptedException("cancelled by executor"))

        try {
            val thrown = runCatching { job.run() }.exceptionOrNull()

            assertThat(thrown).isInstanceOf(IllegalStateException::class.java)
            assertThat(Thread.currentThread().isInterrupted).describedAs("interrupt flag 복원").isTrue()
            assertThat(store.calls).doesNotContain("save", "claim")
            assertThat(sender.sent).isEmpty()
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `adapter 가 인터럽트를 AccountReadException 으로 감싸도 다시 던진다`() {
        korea.failure = AccountReadException(
            AccountReadFailureCategory.NETWORK,
            "request interrupted",
            InterruptedException("cancelled by executor"),
        )

        try {
            assertThat(runCatching { job.run() }.exceptionOrNull()).isInstanceOf(AccountReadException::class.java)
            assertThat(Thread.currentThread().isInterrupted).isTrue()
            assertThat(store.calls).doesNotContain("save", "claim")
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `관측 중 CancellationException 도 다시 던진다`() {
        foreign.failure = java.util.concurrent.CancellationException("lock lost")

        try {
            assertThat(runCatching { job.run() }.exceptionOrNull())
                .isInstanceOf(java.util.concurrent.CancellationException::class.java)
            assertThat(store.calls).doesNotContain("save", "claim")
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `저장 전에 인터럽트됐으면 상태와 메일을 쓰지 않는다`() {
        korea.onRead = { Thread.currentThread().interrupt() }

        try {
            assertThat(job.run()).isEqualTo(JobResult.Skipped("cancelled"))
            assertThat(store.calls).doesNotContain("save", "claim")
            assertThat(store.state).isEqualTo(AccountPnlMonitorState.INITIAL)
            assertThat(sender.sent).isEmpty()
        } finally {
            Thread.interrupted()
        }
    }

    // ---- helpers ----

    private fun givenLastCalculationSucceededLongAgo() {
        store.state = AccountPnlMonitorState(
            PnlAlertState.INITIAL,
            OutageState(t0.minus(Duration.ofMinutes(6)), t0.minus(Duration.ofHours(1)), false),
            7,
        )
    }

    private fun assertNoProfitMail() {
        assertThat(store.rows.map { it.mail.subject }).noneMatch { it.startsWith("[김프 알림] 순수익률") }
    }

    private fun assertProfitMailSent() {
        assertThat(sender.sent.map { it.subject }).anyMatch { it.startsWith("[김프 알림] 순수익률") }
    }

    private fun assertOutageMail(label: String) {
        val outage = store.rows.map { it.mail }.filter { it.subject.startsWith("[김프 알림] 모니터링 중단") }
        assertThat(outage).hasSize(1)
        assertThat(outage.single().subject).contains(label)
    }

    // ---- fakes ----

    class MutableClock(private var now: Instant) : Clock() {
        fun advance(duration: Duration) {
            now = now.plus(duration)
        }

        fun set(instant: Instant) {
            now = instant
        }

        override fun instant(): Instant = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
    }

    class FakeKorea(private val clock: Clock) : KoreaSpotAccountReadPort {
        var quantity = BigDecimal("0.01")
        var bid = BigDecimal("102000000")
        var bidDepth = BigDecimal("1")
        var age: Duration = Duration.ZERO
        var failure: Exception? = null
        var onRead: () -> Unit = {}

        override fun read(): KoreaSpotAccountSnapshot {
            onRead()
            failure?.let { throw it }
            return KoreaSpotAccountSnapshot(
                quantity = quantity,
                averageBuyPrice = BigDecimal("100000000"),
                averageBuyPriceModified = false,
                entryFeeKrw = BigDecimal.ZERO,
                sellFeeRate = BigDecimal.ZERO,
                bidLevels = listOf(PriceLevel(bid, bidDepth)),
                lastPrice = bid,
                bidObservedAt = clock.instant().minus(age),
            )
        }
    }

    class FakeForeign(private val clock: Clock) : ForeignPerpAccountReadPort {
        var signedQuantity = BigDecimal("-0.01")
        var age: Duration = Duration.ZERO
        var failure: Exception? = null

        override fun read(): ForeignPerpAccountSnapshot {
            failure?.let { throw it }
            return ForeignPerpAccountSnapshot(
                signedQuantity = signedQuantity,
                entryPrice = BigDecimal("70000"),
                entryCommissionUsdt = BigDecimal.ZERO,
                fundingUsdt = BigDecimal.ZERO,
                takerFeeRate = BigDecimal.ZERO,
                askLevels = listOf(PriceLevel(BigDecimal("70000"), BigDecimal("1"))),
                lastPrice = BigDecimal("70000"),
                askObservedAt = clock.instant().minus(age),
            )
        }
    }

    class FakeFx(private val clock: Clock) : FxRateReadPort {
        var missing = false
        var observedAt: () -> Instant = { clock.instant().minus(Duration.ofMinutes(1)) }

        override fun findLatest(base: Currency, quote: Currency): ExchangeRateSnapshot? {
            check(base == Currency.USD && quote == Currency.KRW) { "unexpected pair $base/$quote" }
            return if (missing) null else ExchangeRateSnapshot("USD", "KRW", BigDecimal("1400"), observedAt())
        }
    }

    class FakeLastSuccess(private val clock: Clock) : JobLastSuccessReadPort {
        var fxIngestion: () -> Instant? = { clock.instant().minus(Duration.ofMinutes(10)) }

        override fun findLastSuccess(jobId: JobId): Instant? {
            check(jobId == JobId.FX_INGESTION) { "unexpected job $jobId" }
            return fxIngestion()
        }
    }

    class FakeSender : AccountPnlMailSender {
        var failing = false
        var failSubjects: (String) -> Boolean = { false }
        var failure: Exception? = null
        val sent = mutableListOf<AccountPnlMail>()

        override fun send(mail: AccountPnlMail) {
            failure?.let { throw it }
            if (failing || failSubjects(mail.subject)) throw IllegalStateException("smtp down")
            sent += mail
        }
    }

    enum class Status { PENDING, PROCESSING, SENT, FAILED }

    data class Row(
        val id: Long,
        val mail: AccountPnlMail,
        var status: Status = Status.PENDING,
        var token: String? = null,
        var attempts: Int = 0,
        var lockedAt: Instant? = null,
    )

    /** 단일 스레드 in-memory store. version·token·시도 횟수 의미는 `AccountPnlAlertStore` 계약을 따른다. */
    class FakeStore : AccountPnlAlertStore {
        var state = AccountPnlMonitorState.INITIAL
        val rows = mutableListOf<Row>()
        val calls = mutableListOf<String>()
        val owners = mutableListOf<String>()
        val claimLimits = mutableListOf<Int>()
        val claimTimes = mutableListOf<Instant>()
        var failOn: String? = null
        var concurrentWriteBeforeSave = false
        var fenceMarks = false

        private fun enter(operation: String, detail: String = operation) {
            calls += detail
            if (failOn == operation) throw IllegalStateException("store down: $operation")
        }

        override fun load(): AccountPnlMonitorState {
            enter("load")
            return state
        }

        override fun save(expectedVersion: Long, state: AccountPnlMonitorState, mails: List<AccountPnlMail>): Boolean {
            enter("save")
            if (concurrentWriteBeforeSave) this.state = this.state.copy(version = this.state.version + 1)
            if (this.state.version != expectedVersion) return false
            this.state = state.copy(version = expectedVersion + 1)
            mails.filter { mail -> rows.none { it.mail.eventKey == mail.eventKey } }
                .forEach { rows += Row(rows.size + 1L, it) }
            return true
        }

        override fun claim(owner: String, limit: Int, now: Instant): List<ClaimedAccountPnlMail> {
            enter("claim")
            owners += owner
            claimLimits += limit
            claimTimes += now
            return rows.filter { it.status == Status.PENDING }.sortedBy { it.id }.take(limit).map { row ->
                row.status = Status.PROCESSING
                row.token = UUID.randomUUID().toString()
                row.lockedAt = now
                ClaimedAccountPnlMail(row.id, row.mail, row.token!!, row.attempts)
            }
        }

        override fun markSent(id: Long, claimToken: String, sentAt: Instant): Boolean {
            enter("markSent")
            val row = owned(id, claimToken) ?: return false
            row.status = Status.SENT
            row.token = null
            return true
        }

        override fun markAttemptFailed(id: Long, claimToken: String, now: Instant): Boolean {
            enter("markAttemptFailed")
            val row = owned(id, claimToken) ?: return false
            row.attempts += 1
            row.status = if (row.attempts >= AccountPnlAlertStore.MAX_ATTEMPTS) Status.FAILED else Status.PENDING
            row.token = null
            return true
        }

        override fun recoverStale(now: Instant, staleAfter: Duration): Int {
            enter("recoverStale", "recoverStale@$now/$staleAfter")
            val stale = rows.filter { it.status == Status.PROCESSING && it.lockedAt!! < now.minus(staleAfter) }
            stale.forEach {
                it.attempts += 1
                it.status = if (it.attempts >= AccountPnlAlertStore.MAX_ATTEMPTS) Status.FAILED else Status.PENDING
                it.token = null
            }
            return stale.size
        }

        private fun owned(id: Long, token: String): Row? =
            if (fenceMarks) null else rows.firstOrNull { it.id == id && it.status == Status.PROCESSING && it.token == token }
    }
}
