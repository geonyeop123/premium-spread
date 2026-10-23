package io.premiumspread.application.job

import com.fasterxml.jackson.databind.ObjectMapper
import io.premiumspread.application.common.JobExecutor
import io.premiumspread.application.common.JobResult
import io.premiumspread.application.job.accountpnl.AccountPnlMonitorJob
import io.premiumspread.domain.accountpnl.AccountPnlAlertStore
import io.premiumspread.domain.accountpnl.AccountPnlMail
import io.premiumspread.domain.accountpnl.AccountPnlMailSender
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
import io.premiumspread.domain.owneralert.OwnerAlertChannels
import io.premiumspread.domain.owneralert.OwnerAlertRouting
import io.premiumspread.domain.ticker.Currency
import io.premiumspread.infrastructure.batch.notification.DiscordWebhookSenderAdapter
import io.premiumspread.infrastructure.batch.notification.DiscordWebhooks
import io.premiumspread.support.BatchIntegrationTestBase
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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

/**
 * AC11 — owner 알림의 메일·Discord 전달 E2E.
 *
 * 실제 MySQL(V19 형태)·실제 store·실제 [DiscordWebhookSenderAdapter](MockWebServer)·실제 [AccountPnlMonitorJob] 을 쓰고,
 * 메일 sender 만 기록용 fake 다. "재시작"은 같은 store 위에 새 Job 인스턴스를 만드는 것이다.
 */
class OwnerAlertDeliveryIntegrationTest : BatchIntegrationTestBase() {

    @Autowired private lateinit var store: AccountPnlAlertStore

    private val t0 = Instant.parse("2026-10-20T03:00:00Z")
    private val clock = MutableClock(t0)
    private val korea = FakeKorea(clock)
    private val foreign = FakeForeign(clock)
    private val fx = FakeFx(clock)
    private val lastSuccess = JobLastSuccessReadPort { clock.instant().minus(Duration.ofMinutes(10)) }
    private val mailSender = RecordingMailSender()
    private val executors = mutableListOf<JobExecutor>()
    private val server = MockWebServer()
    private val mapper = ObjectMapper()

    @BeforeEach
    fun startServer() {
        server.start()
    }

    @AfterEach
    fun cleanResources() {
        executors.forEach { it.close() }
        runCatching { server.shutdown() }
    }

    private fun newJob(): AccountPnlMonitorJob {
        val executor = JobExecutor(AlwaysLock, NoopRecorder, NoopAlert, clock).also { executors += it }
        val webhooks = DiscordWebhooks(
            pnl = server.url("/api/webhooks/1/TOKENpnlQ7").toUri(),
            market = null,
            ops = server.url("/api/webhooks/3/TOKENopsQ7").toUri(),
        )
        val discord = DiscordWebhookSenderAdapter(webhooks, Duration.ofSeconds(3), Duration.ofSeconds(2)) { }
        return AccountPnlMonitorJob(
            korea,
            foreign,
            fx,
            lastSuccess,
            store,
            mailSender,
            executor,
            clock,
            routing = OwnerAlertRouting(OwnerAlertChannels(email = true, discord = true)),
            discordSender = discord,
        )
    }

    @Test
    fun `수익 알림은 메일과 Discord 로 한 번씩 간다`() {
        server.enqueue(MockResponse().setResponseCode(204))
        korea.bid = BID_1_20

        assertThat(newJob().run()).isEqualTo(JobResult.Success)

        val rows = rows()
        assertThat(rows).hasSize(2)
        assertThat(rows.map { it["event_key"] }.toSet()).hasSize(1)
        assertThat(rows.associate { it["channel"] to it["status"] }).containsExactlyInAnyOrderEntriesOf(
            mapOf("EMAIL" to "SENT", "DISCORD" to "SENT"),
        )
        assertThat(mailSender.sent).hasSize(1)
        assertThat(server.requestCount).isEqualTo(1)
        val request = server.takeRequest()
        assertThat(request.path).isEqualTo("/api/webhooks/1/TOKENpnlQ7")
        assertThat(mapper.readTree(request.body.readUtf8())["content"].asText()).isEqualTo("@here")
        assertNoTokenInOutbox()
    }

    @Test
    fun `Discord 503 은 다음 회차 재시작 뒤 재발송되고 메일은 다시 가지 않는다`() {
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setResponseCode(204))
        korea.bid = BID_1_20

        assertThat(newJob().run()).isEqualTo(JobResult.Success)
        assertThat(statusOf("EMAIL")).isEqualTo("SENT")
        assertThat(statusOf("DISCORD")).isEqualTo("PENDING")
        assertThat(attemptsOf("DISCORD")).isEqualTo(1)
        assertThat(mailSender.sent).hasSize(1)
        assertNoTokenInOutbox()

        clock.advance(Duration.ofMinutes(1))
        assertThat(newJob().run()).isEqualTo(JobResult.Success)

        assertThat(rows()).hasSize(2)
        assertThat(statusOf("DISCORD")).isEqualTo("SENT")
        assertThat(statusOf("EMAIL")).isEqualTo("SENT")
        assertThat(mailSender.sent).hasSize(1)
        assertThat(server.requestCount).isEqualTo(2)
        assertNoTokenInOutbox()
    }

    @Test
    fun `Discord 404 는 한 번에 FAILED 이고 다시 시도하지 않는다`() {
        server.enqueue(MockResponse().setResponseCode(404))
        korea.bid = BID_1_20

        assertThat(newJob().run()).isEqualTo(JobResult.Success)
        assertThat(statusOf("DISCORD")).isEqualTo("FAILED")
        assertThat(attemptsOf("DISCORD")).isEqualTo(1)
        assertThat(server.requestCount).isEqualTo(1)
        assertNoTokenInOutbox()

        clock.advance(Duration.ofMinutes(1))
        assertThat(newJob().run()).isEqualTo(JobResult.Success)

        assertThat(statusOf("DISCORD")).isEqualTo("FAILED")
        assertThat(server.requestCount).isEqualTo(1)
        assertThat(mailSender.sent).hasSize(1)
        assertNoTokenInOutbox()
    }

    // ---- 도우미 ----

    private fun rows(): List<Map<String, Any?>> = jdbcTemplate.queryForList("SELECT * FROM account_pnl_mail ORDER BY id")

    private fun statusOf(channel: String) = jdbcTemplate.queryForObject(
        "SELECT status FROM account_pnl_mail WHERE channel = ?",
        String::class.java,
        channel,
    )

    private fun attemptsOf(channel: String) = jdbcTemplate.queryForObject(
        "SELECT attempt_count FROM account_pnl_mail WHERE channel = ?",
        Int::class.java,
        channel,
    )

    /** outbox 모든 컬럼의 문자열 값에 webhook token 이 없다 (AC11-d). */
    private fun assertNoTokenInOutbox() {
        rows().forEach { row ->
            row.values.forEach { value -> assertThat(value?.toString() ?: "").doesNotContain("TOKEN") }
        }
    }

    private companion object {
        val BID_1_20 = BigDecimal("101200000")
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

    class RecordingMailSender : AccountPnlMailSender {
        val sent: MutableList<AccountPnlMail> = Collections.synchronizedList(mutableListOf())

        override fun send(mail: AccountPnlMail) {
            sent += mail
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
