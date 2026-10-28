package io.premiumspread.application.job

import io.premiumspread.application.common.JobExecutor
import io.premiumspread.application.common.JobResult
import io.premiumspread.application.job.ownerops.OwnerOpsSignalJob
import io.premiumspread.application.notification.owneralert.OwnerAlertDeliveryJob
import io.premiumspread.application.notification.owneralert.OwnerAlertDispatcher
import io.premiumspread.domain.accountpnl.AccountPnlAlertStore
import io.premiumspread.domain.accountpnl.AccountPnlMail
import io.premiumspread.domain.accountpnl.AccountPnlMailSender
import io.premiumspread.domain.accountpnl.OwnerAlertOutbox
import io.premiumspread.domain.batch.BatchMarket
import io.premiumspread.domain.job.JobFailureStreakReadPort
import io.premiumspread.domain.job.JobId
import io.premiumspread.domain.job.JobLastSuccessReadPort
import io.premiumspread.domain.job.JobLock
import io.premiumspread.domain.job.JobRunOutcome
import io.premiumspread.domain.job.JobRunRecorder
import io.premiumspread.domain.job.OperatorAlert
import io.premiumspread.domain.job.OperatorAlertMessage
import io.premiumspread.domain.market.LatestMarketTickReadPort
import io.premiumspread.domain.market.MarketPair
import io.premiumspread.domain.market.MarketTick
import io.premiumspread.domain.owneralert.DiscordMessage
import io.premiumspread.domain.owneralert.DiscordPush
import io.premiumspread.domain.owneralert.DiscordRoute
import io.premiumspread.domain.owneralert.DiscordTarget
import io.premiumspread.domain.owneralert.OwnerAlertChannels
import io.premiumspread.domain.owneralert.OwnerAlertDiscordSender
import io.premiumspread.domain.owneralert.OwnerAlertRouting
import io.premiumspread.domain.ticker.Currency
import io.premiumspread.domain.ticker.Exchange
import io.premiumspread.domain.ticker.Quote
import io.premiumspread.domain.ticker.Symbol
import io.premiumspread.support.BatchIntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * AC12 — owner 운영 알림 E2E (신호 Job → 전달 Job → 재알림 → 복구, 재시작, 동시 전달).
 *
 * 실제 MySQL·실제 `JdbcOwnerAlertOutbox`·실제 store 를 쓰고 메일·Discord sender 와 관측 port 만 기록용 fake 다.
 * fake port 시각은 [MutableClock] 기준 상대값이고, 장애로 만들 신호만 고정 절대 시각으로 덮는다.
 */
class OwnerOpsAlertIntegrationTest : BatchIntegrationTestBase() {

    @Autowired private lateinit var store: AccountPnlAlertStore

    @Autowired private lateinit var outbox: OwnerAlertOutbox

    private val start = Instant.parse("2026-10-28T00:00:00Z")
    private val clock = MutableClock(start)
    private val lastSuccess = RelativeLastSuccess(clock)
    private val ticks = RelativeTicks(clock)
    private val mailSender = RecordingMailSender()
    private val discordSender = RecordingDiscordSender()
    private val executors = mutableListOf<JobExecutor>()
    private val threads = Executors.newFixedThreadPool(2)

    @BeforeEach
    fun cleanOwnerOpsTables() {
        // BatchTestConfig.BATCH_TABLES 에 없는 V21 테이블
        jdbcTemplate.execute("TRUNCATE TABLE `owner_alert_signal_state`")
    }

    @AfterEach
    fun cleanResources() {
        executors.forEach { it.close() }
        threads.shutdownNow()
        assertThat(threads.awaitTermination(10, TimeUnit.SECONDS)).isTrue()
    }

    private fun newExecutor() = JobExecutor(AlwaysLock, NoopRecorder, NoopAlert, clock).also { executors += it }

    private fun newSignalJob() = OwnerOpsSignalJob(
        lastSuccess,
        JobFailureStreakReadPort { null },
        ticks,
        { MARKET },
        outbox,
        ROUTING,
        newExecutor(),
        clock,
    )

    private fun newDeliveryJob() = OwnerAlertDeliveryJob(store, mailSender, discordSender, newExecutor(), clock)

    /** 신호 회차 → 전달 회차. */
    private fun cycle(signalJob: OwnerOpsSignalJob) {
        assertThat(signalJob.run()).isEqualTo(JobResult.Success)
        assertThat(newDeliveryJob().run()).isEqualTo(JobResult.Success)
    }

    @Test
    fun `(a)(b)(c) FX 장애는 알림 이후 6시간 뒤 재알림되고 복구 알림으로 닫힌다`() {
        val signalJob = newSignalJob()
        lastSuccess.overrides[JobId.FX_INGESTION] = Instant.parse("2026-10-27T23:29:00Z")

        // (a) 31분 전 마지막 성공 → alert-1 이 OPS Discord 로 한 번 간다 (운영 알림 기본 경로는 Discord 전용)
        cycle(signalJob)

        val first = rows()
        assertThat(first).hasSize(1)
        val firstKey = first.map { it["event_key"] as String }.toSet().single()
        assertThat(firstKey).matches("owner-ops\\.fx-ingestion\\.usd-krw\\.\\d+\\.alert-1")
        assertThat(first.single()["channel"]).isEqualTo("DISCORD")
        assertThat(first.single()["status"]).isEqualTo("SENT")
        assertThat(discordSender.sent.map { it.first }).containsExactly(DiscordRoute(DiscordTarget.OPS, DiscordPush.SOUND))
        assertThat(mailSender.sent).isEmpty()
        assertThat(activeOf()).isEqualTo(1)

        // (b) +6h → alert-2 만 추가되고 다른 신호의 행은 없다
        clock.advance(Duration.ofHours(6))
        cycle(signalJob)

        val keys = rows().map { it["event_key"] as String }.toSet()
        assertThat(keys).hasSize(2)
        assertThat(rows().map { it["channel"] }).containsOnly("DISCORD")
        assertThat(keys).allMatch { it.startsWith("owner-ops.fx-ingestion.usd-krw.") }
        assertThat(keys.filter { it.endsWith(".alert-2") }).hasSize(1)
        assertThat(rows()).hasSize(2).allSatisfy { assertThat(it["status"]).isEqualTo("SENT") }
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM owner_alert_signal_state", Int::class.java)).isEqualTo(1)

        // (c) 성공 기록이 돌아오면 recovered 가 무음 OPS 로 한 번 간다
        lastSuccess.overrides.remove(JobId.FX_INGESTION)
        discordSender.sent.clear()
        cycle(signalJob)

        val recovered = rows().filter { (it["event_key"] as String).endsWith(".recovered") }
        assertThat(recovered).hasSize(1)
        assertThat(recovered.map { it["status"] }).containsOnly("SENT")
        assertThat(discordSender.sent.map { it.first }).containsExactly(DiscordRoute(DiscordTarget.OPS, DiscordPush.SILENT))
        assertThat(activeOf()).isZero()
        assertThat(rows()).hasSize(3)
        assertThat(mailSender.sent).isEmpty()
    }

    @Test
    fun `(d) 장애 중 재시작해도 근거 없음 구간에는 새 행이 없고 장애 상태가 유지된다`() {
        lastSuccess.overrides[JobId.FX_INGESTION] = Instant.parse("2026-10-27T23:29:00Z")
        cycle(newSignalJob())
        val before = rows().size
        assertThat(before).isEqualTo(1)

        // 재시작: 새 Job 인스턴스, 성공 기록은 아직 읽을 수 없다
        val restarted = newSignalJob()
        lastSuccess.overrides[JobId.FX_INGESTION] = null
        repeat(RESTART_ROUNDS) {
            assertThat(restarted.run()).isEqualTo(JobResult.Success)
            clock.advance(Duration.ofSeconds(30))
        }
        assertThat(newDeliveryJob().run()).isEqualTo(JobResult.Success)

        assertThat(rows()).hasSize(before)
        assertThat(activeOf()).isEqualTo(1)
    }

    @Test
    fun `(e) 전달 Job 과 PnL owner 디스패처가 동시에 돌아도 행마다 한 번만 발송된다`() {
        val events = (1..6).map { "owner-ops.e2e.$it.alert-1" }
        val queued = events.map { AccountPnlMail(it, "[운영] subject $it", "body $it") }
        assertThat(outbox.enqueue(emptyList(), queued)).isTrue()
        assertThat(rows()).hasSize(6).allSatisfy { assertThat(it["status"]).isEqualTo("PENDING") }

        val deliveryJob = newDeliveryJob()
        val pnlDispatcher = OwnerAlertDispatcher(store, mailSender, discordSender, clock, "account-pnl-monitor:e2e")
        val ready = CountDownLatch(2)
        val go = CountDownLatch(1)
        val futures = listOf<() -> JobResult>({ deliveryJob.run() }, { pnlDispatcher.dispatch() }).map { action ->
            threads.submit<JobResult> {
                ready.countDown()
                assertThat(go.await(30, TimeUnit.SECONDS)).isTrue()
                action()
            }
        }
        assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue()
        go.countDown()
        futures.forEach { assertThat(it.get(60, TimeUnit.SECONDS)).isEqualTo(JobResult.Success) }

        val counts = mailSender.sent.groupingBy { it.eventKey }.eachCount()
        assertThat(counts).containsOnlyKeys(events)
        assertThat(counts.values).containsOnly(1)
        assertThat(rows()).hasSize(6).allSatisfy { assertThat(it["status"]).isEqualTo("SENT") }
    }

    // ---- 도우미 ----

    private fun rows(): List<Map<String, Any?>> = jdbcTemplate.queryForList("SELECT * FROM account_pnl_mail ORDER BY id")

    private fun activeOf(): Int =
        jdbcTemplate.queryForObject("SELECT active FROM owner_alert_signal_state", Int::class.java)!!

    private companion object {
        const val RESTART_ROUNDS = 58 // 30초 간격 x 58 = 29분
        val BTC = Symbol("BTC")
        val MARKET = BatchMarket(
            MarketPair.default(BTC),
            Quote.coin(BTC, Currency.KRW),
            Quote.coin(BTC, Currency.USD),
            Currency.USD,
            Currency.KRW,
        )
        val ROUTING = OwnerAlertRouting(OwnerAlertChannels(email = true, discord = true))
    }

    // integrationTest source set 은 test source set 의 fake 를 쓸 수 없어 필요한 최소분을 복제한다.

    class MutableClock(private var now: Instant) : Clock() {
        @Synchronized fun advance(duration: Duration) {
            now = now.plus(duration)
        }

        @Synchronized override fun instant(): Instant = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
    }

    /** 기본값은 호출 시점 `clock - 1분`(정상). [overrides] 의 값(null 포함)이 우선한다. */
    class RelativeLastSuccess(private val clock: Clock) : JobLastSuccessReadPort {
        val overrides: MutableMap<JobId, Instant?> = Collections.synchronizedMap(mutableMapOf())

        override fun findLastSuccess(jobId: JobId): Instant? =
            if (jobId in overrides) overrides[jobId] else clock.instant().minus(Duration.ofMinutes(1))
    }

    /** 호출 시점 `clock - 1초`(정상) tick. */
    class RelativeTicks(private val clock: Clock) : LatestMarketTickReadPort {
        override fun findLatest(exchange: Exchange, quote: Quote) =
            MarketTick(exchange, quote, BigDecimal("100"), clock.instant().minus(Duration.ofSeconds(1)))
    }

    class RecordingMailSender : AccountPnlMailSender {
        val sent: MutableList<AccountPnlMail> = Collections.synchronizedList(mutableListOf())

        override fun send(mail: AccountPnlMail) {
            sent += mail
        }
    }

    class RecordingDiscordSender : OwnerAlertDiscordSender {
        val sent: MutableList<Pair<DiscordRoute, DiscordMessage>> = Collections.synchronizedList(mutableListOf())

        override fun send(route: DiscordRoute, message: DiscordMessage) {
            sent += route to message
        }
    }

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
