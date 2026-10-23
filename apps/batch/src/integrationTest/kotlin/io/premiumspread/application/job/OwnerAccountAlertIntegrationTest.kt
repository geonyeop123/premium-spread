package io.premiumspread.application.job

import io.premiumspread.application.common.JobExecutor
import io.premiumspread.application.common.JobResult
import io.premiumspread.application.job.accountpnl.AccountPnlMonitorJob
import io.premiumspread.domain.accountpnl.AccountAlertSettings
import io.premiumspread.domain.accountpnl.AccountPnlAlertStore
import io.premiumspread.domain.accountpnl.AccountPnlMail
import io.premiumspread.domain.accountpnl.AccountPnlMailSender
import io.premiumspread.domain.accountpnl.ForeignPerpAccountReadPort
import io.premiumspread.domain.accountpnl.ForeignPerpAccountSnapshot
import io.premiumspread.domain.accountpnl.ForeignPerpPositionSource
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
import io.premiumspread.domain.owneralert.DiscordMessage
import io.premiumspread.domain.owneralert.DiscordRoute
import io.premiumspread.domain.owneralert.OwnerAlertChannels
import io.premiumspread.domain.owneralert.OwnerAlertDiscordSender
import io.premiumspread.domain.owneralert.OwnerAlertRouting
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
import java.util.Collections

/**
 * AC12 — owner 계정 알림(포지션 변경·순수익률 급변·미반영 리마인더)의 E2E.
 *
 * 실제 MySQL(V18+V20 형태 fixture)·실제 [AccountPnlAlertStore]·실제 [AccountPnlMonitorJob] 을 쓰고,
 * 계정·환율·lock 과 메일·Discord sender 만 fake 다. "재시작"은 같은 store 위에 새 Job 인스턴스를 만드는 것이다.
 */
class OwnerAccountAlertIntegrationTest : BatchIntegrationTestBase() {

    @Autowired private lateinit var store: AccountPnlAlertStore

    private val clock = MutableClock(Instant.parse("2026-10-19T23:30:00Z")) // 08:30 KST
    private val korea = FakeKorea(clock)
    private val foreign = FakeForeign(clock)
    private val fx = FakeFx(clock)
    private val lastSuccess = JobLastSuccessReadPort { clock.instant().minus(Duration.ofMinutes(10)) }
    private val mailSender = RecordingMailSender()
    private val discordSender = RecordingDiscordSender()
    private val executors = mutableListOf<JobExecutor>()

    @AfterEach
    fun closeExecutors() {
        executors.forEach { it.close() }
    }

    private fun newJob(): AccountPnlMonitorJob {
        val executor = JobExecutor(AlwaysLock, NoopRecorder, NoopAlert, clock).also { executors += it }
        return AccountPnlMonitorJob(
            korea,
            foreign,
            fx,
            lastSuccess,
            store,
            mailSender,
            executor,
            clock,
            hourlyReportEnabled = false,
            routing = OwnerAlertRouting(OwnerAlertChannels(email = true, discord = true)),
            discordSender = discordSender,
            accountAlerts = AccountAlertSettings(),
        )
    }

    private fun runAt(instant: String, job: AccountPnlMonitorJob = newJob()) {
        clock.set(Instant.parse(instant))
        assertThat(job.run()).isEqualTo(JobResult.Success)
    }

    @Test
    fun `포지션 변경 급변 리마인더가 재시작을 넘어 DB 상태로 한 번씩만 나간다`() {
        // 1. 첫 회차 — 알림 없음, leg 4열·펀딩 변경 시각 기록, 08:30 KST 라 리마인더 판정 전 (AC12-a·f)
        runAt("2026-10-19T23:30:00Z")
        assertThat(outboxCount()).isZero()
        assertThat(stateText("korea_quantity")).isEqualTo("0.5")
        assertThat(stateText("korea_average_buy_price")).isEqualTo("100000000")
        assertThat(stateText("foreign_signed_quantity")).isEqualTo("-0.5")
        assertThat(stateText("foreign_entry_price")).isEqualTo("70000")
        assertThat(stateTime("manual_funding_changed_at")).isEqualTo("2026-10-19T23:30:00")
        assertThat(stateText("reminder_day_start")).isNull()

        // 2. 빗썸만 변경 — EMAIL·DISCORD 각 1행 SENT, 헤지 불일치 경고 (AC12-b)
        korea.quantity = BigDecimal("0.60000000")
        runAt("2026-10-19T23:31:00Z")
        val positionKey = eventKeys("account-pnl-position-%").single()
        assertThat(rowsOf(positionKey).associate { it["channel"] to it["status"] }).containsExactlyInAnyOrderEntriesOf(
            mapOf("EMAIL" to "SENT", "DISCORD" to "SENT"),
        )
        assertThat(stateText("hedge_mismatch_warned")).isEqualTo("1")
        assertThat(discordSender.sent.single().second.title).contains("헤지 불일치")

        // 3. 재시작 + 바이낸스·펀딩 변경 — 경고 없는 변경 1건, 경고 해소, 펀딩 변경 시각 갱신 (AC12-c·f)
        foreign.signedQuantity = BigDecimal("-0.60000000")
        foreign.fundingUsdt = BigDecimal("4.00")
        runAt("2026-10-19T23:32:00Z", newJob())
        assertThat(eventKeys("account-pnl-position-%")).hasSize(2)
        assertThat(discordSender.sent).hasSize(2)
        assertThat(discordSender.sent.last().second.title).doesNotContain("헤지 불일치")
        assertThat(stateText("hedge_mismatch_warned")).isEqualTo("0")
        assertThat(stateTime("manual_funding_changed_at")).isEqualTo("2026-10-19T23:32:00")

        // 4. 09:00 KST 새 버킷 — 기준선 저장, 항목이 있으므로 리마인더 1건 (AC12-d·e)
        runAt("2026-10-20T00:00:05Z")
        assertThat(stateTime("swing_bucket_start")).isEqualTo("2026-10-20T00:00:00")
        assertThat(stateText("swing_baseline_percent")).isNotNull()
        val reminderKey = "account-pnl-reminder-${Instant.parse("2026-10-19T15:00:00Z").toEpochMilli()}"
        assertThat(eventKeys("account-pnl-reminder-%")).containsExactly(reminderKey)
        assertThat(rowsOf(reminderKey).map { it["channel"] }).containsExactly("DISCORD")
        assertThat(stateTime("reminder_day_start")).isEqualTo("2026-10-19T15:00:00")

        // 5. 같은 버킷에서 순수익률이 기준선보다 여유 있게 오름 — 상승 급변 DISCORD 1행 (AC12-d)
        korea.bid = BID_UP
        runAt("2026-10-20T00:01:00Z")
        val swingKey = "account-pnl-swing-up-${Instant.parse("2026-10-20T00:00:00Z").toEpochMilli()}"
        assertThat(eventKeys("account-pnl-swing-%")).containsExactly(swingKey)
        assertThat(rowsOf(swingKey).map { it["channel"] }).containsExactly("DISCORD")

        // 6. 같은 날·같은 버킷 다음 회차 — 리마인더·급변 행 증가 없음 (AC12-e)
        runAt("2026-10-20T00:02:00Z")
        assertThat(eventKeys("account-pnl-reminder-%")).hasSize(1)
        assertThat(eventKeys("account-pnl-swing-%")).hasSize(1)
        assertThat(eventKeys("account-pnl-position-%")).hasSize(2)
    }

    // ---- 도우미 ----

    private fun outboxCount() = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM account_pnl_mail", Int::class.java)!!

    private fun eventKeys(like: String) = jdbcTemplate.queryForList(
        "SELECT DISTINCT event_key FROM account_pnl_mail WHERE event_key LIKE ? ORDER BY event_key",
        String::class.java,
        like,
    )

    private fun rowsOf(eventKey: String) =
        jdbcTemplate.queryForList("SELECT channel, status FROM account_pnl_mail WHERE event_key = ?", eventKey)

    private fun stateText(column: String): String? =
        jdbcTemplate.queryForList("SELECT CAST($column AS CHAR) FROM account_pnl_monitor_state", String::class.java)
            .singleOrNull()

    private fun stateTime(column: String): String? = jdbcTemplate.queryForList(
        "SELECT DATE_FORMAT($column, '%Y-%m-%dT%H:%i:%s') FROM account_pnl_monitor_state",
        String::class.java,
    ).singleOrNull()

    private companion object {
        val BID_BASE = BigDecimal("100000000")
        val BID_UP = BigDecimal("100800000") // 기준선보다 약 +0.8%p (임계값 0.50 에 여유)
        val FX_RATE = BigDecimal("1400")
    }

    class MutableClock(private var now: Instant) : Clock() {
        @Synchronized fun set(instant: Instant) {
            now = instant
        }

        @Synchronized override fun instant(): Instant = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
    }

    /** 빗썸 0.5 BTC · 평단 1억, 진입 수수료 미확인(null) — 리마인더 항목 1개. 순수익률은 매수호가로 정해진다. */
    class FakeKorea(private val clock: Clock) : KoreaSpotAccountReadPort {
        @Volatile var quantity: BigDecimal = BigDecimal("0.50000000")

        @Volatile var bid: BigDecimal = BID_BASE

        override fun read() = KoreaSpotAccountSnapshot(
            quantity = quantity,
            averageBuyPrice = BigDecimal("100000000.00"),
            averageBuyPriceModified = false,
            entryFeeKrw = null,
            sellFeeRate = BigDecimal.ZERO,
            bidLevels = listOf(PriceLevel(bid, BigDecimal.TEN)),
            lastPrice = bid,
            bidObservedAt = clock.instant(),
        )
    }

    /** 바이낸스는 수동 입력(env) 출처: -0.5 BTC · 진입가 70000, 펀딩 3.2. */
    class FakeForeign(private val clock: Clock) : ForeignPerpAccountReadPort {
        @Volatile var signedQuantity: BigDecimal = BigDecimal("-0.50000000")

        @Volatile var fundingUsdt: BigDecimal = BigDecimal("3.20")

        override fun read() = ForeignPerpAccountSnapshot(
            signedQuantity = signedQuantity,
            entryPrice = BigDecimal("70000.00"),
            entryCommissionUsdt = BigDecimal.ZERO,
            fundingUsdt = fundingUsdt,
            takerFeeRate = BigDecimal.ZERO,
            askLevels = listOf(PriceLevel(BigDecimal("70000"), BigDecimal.TEN)),
            lastPrice = BigDecimal("70000"),
            askObservedAt = clock.instant(),
            positionSource = ForeignPerpPositionSource.MANUAL_ENV,
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
