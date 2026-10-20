package io.premiumspread.application.job

import io.premiumspread.application.common.JobExecutor
import io.premiumspread.application.common.JobResult
import io.premiumspread.application.job.accountpnl.AccountPnlMonitorJob
import io.premiumspread.domain.accountpnl.AccountHedgePosition
import io.premiumspread.domain.accountpnl.AccountNetPnl
import io.premiumspread.domain.accountpnl.AccountPnlAlertStore
import io.premiumspread.domain.accountpnl.AccountPnlMail
import io.premiumspread.domain.accountpnl.AccountPnlMailSender
import io.premiumspread.domain.accountpnl.AccountPnlMonitorState
import io.premiumspread.domain.accountpnl.ForeignPerpAccountReadPort
import io.premiumspread.domain.accountpnl.ForeignPerpAccountSnapshot
import io.premiumspread.domain.accountpnl.KoreaSpotAccountReadPort
import io.premiumspread.domain.accountpnl.KoreaSpotAccountSnapshot
import io.premiumspread.domain.accountpnl.PriceLevel
import io.premiumspread.domain.exchangerate.ExchangeRateSnapshot
import io.premiumspread.domain.job.JobId
import io.premiumspread.domain.job.JobLastSuccessReadPort
import io.premiumspread.domain.job.JobLock
import io.premiumspread.domain.job.JobRunOutcome
import io.premiumspread.domain.job.JobRunRecorder
import io.premiumspread.domain.job.OperatorAlert
import io.premiumspread.domain.job.OperatorAlertMessage
import io.premiumspread.domain.market.FxRateReadPort
import io.premiumspread.domain.ticker.Currency
import io.premiumspread.support.BatchIntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * AC6 — 실계정 순손익 알림의 재시작 내구성, at-least-once, 동시 인스턴스 단일 발송, stale claim fencing.
 *
 * 실제 MySQL 과 실제 [AccountPnlAlertStore] 구현, 실제 [AccountPnlMonitorJob] 을 쓴다. "재시작"은 같은 store 위에
 * 새 Job 인스턴스를 만드는 것이다 — Job 은 메모리에 상태를 두지 않으므로 그것이 곧 프로세스 재시작과 같다.
 * 외부 경계(계정 조회 port, 환율, 메일 발송, Redis lock)만 fake 다.
 */
class AccountPnlAlertDurabilityIntegrationTest : BatchIntegrationTestBase() {

    @Autowired private lateinit var store: AccountPnlAlertStore

    private val t0 = Instant.parse("2026-10-20T03:00:00Z")
    private val clock = MutableClock(t0)
    private val korea = FakeKorea(clock)
    private val foreign = FakeForeign(clock)
    private val fx = FakeFx(clock)
    private val lastSuccess = JobLastSuccessReadPort { clock.instant().minus(Duration.ofMinutes(10)) }
    private val sender = RecordingSender()
    private val executors = mutableListOf<JobExecutor>()

    @AfterEach
    fun closeExecutors() {
        executors.forEach { it.close() }
    }

    private fun newJob(): AccountPnlMonitorJob {
        val executor = JobExecutor(AlwaysLock, NoopRecorder, NoopAlert, clock).also { executors += it }
        return AccountPnlMonitorJob(korea, foreign, fx, lastSuccess, store, sender, executor, clock)
    }

    @Test
    fun `시나리오가 의도한 순수익률을 만든다`() {
        assertThat(netPercent(BID_1_20)).isBetween(BigDecimal("1.15"), BigDecimal("1.25"))
        assertThat(netPercent(BID_1_30)).isBetween(BigDecimal("1.25"), BigDecimal("1.35"))
        assertThat(netPercent(BID_BELOW)).isLessThan(BigDecimal("0.90"))
    }

    @Test
    fun `재시작해도 재발송 타이머가 유지되어 30분 뒤에는 늘지 않고 60분 뒤에는 한 건 늘어난다`() {
        korea.bid = BID_1_20
        assertThat(newJob().run()).isEqualTo(JobResult.Success)
        assertThat(mailCount()).isEqualTo(1)
        assertThat(sender.sent).hasSize(1)

        clock.advance(Duration.ofMinutes(30))
        korea.bid = BID_1_30
        assertThat(newJob().run()).isEqualTo(JobResult.Success)
        assertThat(mailCount()).isEqualTo(1)
        assertThat(sender.sent).hasSize(1)

        clock.advance(Duration.ofMinutes(30))
        assertThat(newJob().run()).isEqualTo(JobResult.Success)
        assertThat(mailCount()).isEqualTo(2)
        assertThat(sender.sent).hasSize(2)
        assertThat(statuses()).containsExactly("SENT", "SENT")
    }

    @Test
    fun `첫 발송이 실패하면 PENDING 으로 남고 다음 회차에 새 인스턴스가 보내 SENT 로 끝난다`() {
        korea.bid = BID_1_20
        sender.failuresLeft.set(1)

        assertThat(newJob().run()).isEqualTo(JobResult.Success)
        assertThat(statuses()).containsExactly("PENDING")
        assertThat(attempts()).containsExactly(1)
        assertThat(sender.sent).isEmpty()

        clock.advance(Duration.ofMinutes(1))
        assertThat(newJob().run()).isEqualTo(JobResult.Success)

        assertThat(mailCount()).isEqualTo(1)
        assertThat(statuses()).containsExactly("SENT")
        assertThat(sender.sent).hasSize(1)
    }

    @Test
    fun `두 인스턴스가 같은 시각에 동시에 돌아도 메일 row 와 발송은 한 번이다`() {
        korea.bid = BID_1_20
        val jobs = listOf(newJob(), newJob())
        val pool = Executors.newFixedThreadPool(2)
        try {
            val start = CountDownLatch(1)
            val futures = jobs.map { job ->
                pool.submit<JobResult> {
                    start.await()
                    job.run()
                }
            }
            start.countDown()
            val results = futures.map { it.get(60, TimeUnit.SECONDS) }

            assertThat(results).noneMatch { it is JobResult.Failure }
            assertThat(results).allMatch { it == JobResult.Success || it is JobResult.Skipped }
            assertThat(mailCount()).isEqualTo(1)
            assertThat(sender.sent).hasSize(1)
            assertThat(statuses()).containsExactly("SENT")
        } finally {
            pool.shutdownNow()
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue()
        }
    }

    @Test
    fun `발송 중 claim 이 stale 복구된 뒤 늦은 markSent 는 거부되고 재claim 한 쪽이 SENT 로 만든다`() {
        assertThat(store.save(0, AccountPnlMonitorState.INITIAL, listOf(AccountPnlMail("k-stale", "제목", "본문")))).isTrue()
        val stale = store.claim("old-owner", 10, t0).single()

        clock.advance(Duration.ofMinutes(6))
        korea.bid = BID_BELOW // 새 알림은 만들지 않고 복구된 메일만 다시 보낸다
        assertThat(newJob().run()).isEqualTo(JobResult.Success)

        assertThat(store.markSent(stale.id, stale.claimToken, clock.instant())).isFalse()
        assertThat(store.markAttemptFailed(stale.id, stale.claimToken, clock.instant())).isFalse()
        assertThat(mailCount()).isEqualTo(1)
        assertThat(statuses()).containsExactly("SENT")
        assertThat(attempts()).containsExactly(1)
        assertThat(sender.sent.map { it.eventKey }).containsExactly("k-stale")
    }

    // ---- 도우미 ----

    private fun mailCount() = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM account_pnl_mail", Int::class.java)!!

    private fun statuses() = jdbcTemplate.queryForList("SELECT status FROM account_pnl_mail ORDER BY id", String::class.java)

    private fun attempts() = jdbcTemplate.queryForList("SELECT attempt_count FROM account_pnl_mail ORDER BY id", Int::class.java)

    private fun netPercent(bid: BigDecimal): BigDecimal {
        korea.bid = bid
        val position = checkNotNull(AccountHedgePosition.resolve(korea.read(), foreign.read()))
        return AccountNetPnl.calculate(position, FX_RATE, t0, t0).netPercent
    }

    private companion object {
        val BID_1_20 = BigDecimal("101200000")
        val BID_1_30 = BigDecimal("101300000")
        val BID_BELOW = BigDecimal("100500000")
        val FX_RATE = BigDecimal("1400")
    }

    class MutableClock(private var now: Instant) : Clock() {
        @Synchronized fun advance(duration: Duration) {
            now = now.plus(duration)
        }

        @Synchronized override fun instant(): Instant = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
    }

    /** 평단 1억 · 0.01 BTC, 바이낸스는 70000 USDT 에 -0.01 숏, 수수료 0. 순수익률은 빗썸 매수호가만으로 정해진다. */
    class FakeKorea(private val clock: Clock) : KoreaSpotAccountReadPort {
        @Volatile var bid: BigDecimal = BigDecimal("102000000")

        override fun read() = KoreaSpotAccountSnapshot(
            quantity = BigDecimal("0.01"),
            averageBuyPrice = BigDecimal("100000000"),
            averageBuyPriceModified = false,
            entryFeeKrw = BigDecimal.ZERO,
            sellFeeRate = BigDecimal.ZERO,
            bidLevels = listOf(PriceLevel(bid, BigDecimal.ONE)),
            lastPrice = bid,
            bidObservedAt = clock.instant(),
        )
    }

    class FakeForeign(private val clock: Clock) : ForeignPerpAccountReadPort {
        override fun read() = ForeignPerpAccountSnapshot(
            signedQuantity = BigDecimal("-0.01"),
            entryPrice = BigDecimal("70000"),
            entryCommissionUsdt = BigDecimal.ZERO,
            fundingUsdt = BigDecimal.ZERO,
            takerFeeRate = BigDecimal.ZERO,
            askLevels = listOf(PriceLevel(BigDecimal("70000"), BigDecimal.ONE)),
            lastPrice = BigDecimal("70000"),
            askObservedAt = clock.instant(),
        )
    }

    class FakeFx(private val clock: Clock) : FxRateReadPort {
        override fun findLatest(base: Currency, quote: Currency) =
            ExchangeRateSnapshot("USD", "KRW", FX_RATE, clock.instant().minus(Duration.ofMinutes(1)))
    }

    class RecordingSender : AccountPnlMailSender {
        val failuresLeft = AtomicInteger(0)
        val sent: MutableList<AccountPnlMail> = java.util.Collections.synchronizedList(mutableListOf())

        override fun send(mail: AccountPnlMail) {
            check(failuresLeft.getAndUpdate { if (it > 0) it - 1 else it } <= 0) { "smtp down" }
            sent += mail
        }
    }

    /** Redis lock 을 우회한다 — 동시 실행 방어를 lock 이 아니라 DB version·claim 이 해야 함을 보이기 위해서다. */
    object AlwaysLock : JobLock {
        override fun tryAcquire(key: String, owner: String, lease: Duration, acquiredAt: Instant) = true
        override fun renew(key: String, owner: String, lease: Duration) = true
        override fun release(key: String, owner: String) = Unit
    }

    object NoopRecorder : JobRunRecorder {
        override fun started(jobId: JobId, runId: String, at: Instant) = Unit
        override fun completed(jobId: JobId, runId: String, outcome: JobRunOutcome, at: Instant, detail: String?) = Unit
    }

    object NoopAlert : OperatorAlert {
        override fun send(alert: OperatorAlertMessage) = Unit
    }
}
