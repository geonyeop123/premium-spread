package io.premiumspread.application.job

import io.premiumspread.application.common.JobExecutor
import io.premiumspread.application.common.JobResult
import io.premiumspread.application.job.ownermarket.OwnerMarketSignalJob
import io.premiumspread.application.notification.owneralert.OwnerAlertDeliveryJob
import io.premiumspread.domain.accountpnl.AccountPnlAlertStore
import io.premiumspread.domain.accountpnl.AccountPnlLatest
import io.premiumspread.domain.accountpnl.AccountPnlLatestStore
import io.premiumspread.domain.accountpnl.AccountPnlMail
import io.premiumspread.domain.accountpnl.AccountPnlMailSender
import io.premiumspread.domain.aggregation.PremiumAggregationReadPort
import io.premiumspread.domain.batch.BatchMarket
import io.premiumspread.domain.exchangerate.ExchangeRateSnapshot
import io.premiumspread.domain.job.JobId
import io.premiumspread.domain.job.JobLock
import io.premiumspread.domain.job.JobRunOutcome
import io.premiumspread.domain.job.JobRunRecorder
import io.premiumspread.domain.job.OperatorAlert
import io.premiumspread.domain.job.OperatorAlertMessage
import io.premiumspread.domain.market.FxRateReadPort
import io.premiumspread.domain.market.MarketPair
import io.premiumspread.domain.owneralert.DiscordMessage
import io.premiumspread.domain.owneralert.DiscordPush
import io.premiumspread.domain.owneralert.DiscordRoute
import io.premiumspread.domain.owneralert.DiscordTarget
import io.premiumspread.domain.owneralert.OwnerAlertChannels
import io.premiumspread.domain.owneralert.OwnerAlertDiscordSender
import io.premiumspread.domain.owneralert.OwnerAlertRouting
import io.premiumspread.domain.ownermarket.MarketAlertSettings
import io.premiumspread.domain.ownermarket.OwnerMarketAlertStore
import io.premiumspread.domain.premium.PremiumReadPort
import io.premiumspread.domain.premium.PremiumSnapshot
import io.premiumspread.domain.ticker.Currency
import io.premiumspread.domain.ticker.Quote
import io.premiumspread.domain.ticker.Symbol
import io.premiumspread.redis.AggregationTimeUnit
import io.premiumspread.redis.RedisKeyGenerator
import io.premiumspread.support.BatchIntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.sql.Date
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Collections

/**
 * AC15 - owner 시장 알림 E2E (시장 신호 Job -> 상태 저장소 -> 전달 Job -> 기록 sender).
 *
 * 실제 MySQL/Redis, 실제 store, 실제 `PremiumAggregationReadPort`(DB 집계 행)를 쓰고 현재 김프·환율과 sender 만 기록용 fake 다.
 */
class OwnerMarketAlertIntegrationTest : BatchIntegrationTestBase() {

    @Autowired private lateinit var marketStore: OwnerMarketAlertStore

    @Autowired private lateinit var alertStore: AccountPnlAlertStore

    @Autowired private lateinit var pnlLatest: AccountPnlLatestStore

    @Autowired private lateinit var aggregations: PremiumAggregationReadPort

    private val clock = MutableClock(START)
    private val current = FakeCurrent(clock)
    private val fx = FakeFx(clock)
    private val mailSender = RecordingMailSender()
    private val discordSender = RecordingDiscordSender()
    private val executors = mutableListOf<JobExecutor>()

    @BeforeEach
    fun cleanMarketAlertState() {
        listOf(
            "owner_market_alert_state", "account_pnl_latest", "account_pnl_mail",
            "premium_minute", "premium_hour", "premium_day",
        ).forEach { jdbcTemplate.execute("TRUNCATE TABLE `$it`") }
        // PremiumAggregationCacheReader 는 기본 pair 의 legacy key 까지 읽어 DB 결과와 병합한다 - 둘 다 비운다
        val keys = listOf(AggregationTimeUnit.MINUTES, AggregationTimeUnit.HOURS, AggregationTimeUnit.DAYS).flatMap {
            listOf(
                RedisKeyGenerator.premiumV2AggregationKey("BITHUMB", "BINANCE", "BTC", it.name),
                it.keyFor("BTC"),
            )
        }
        redisTemplate.delete(keys)
    }

    @AfterEach
    fun cleanResources() {
        executors.forEach { it.close() }
    }

    private fun newExecutor() = JobExecutor(AlwaysLock, NoopRecorder, NoopAlert, clock).also { executors += it }

    private fun newSignalJob(settings: MarketAlertSettings = QUIET_SUMMARY) = OwnerMarketSignalJob(
        current,
        aggregations,
        fx,
        pnlLatest,
        { MARKET },
        marketStore,
        ROUTING,
        newExecutor(),
        clock,
        settings,
        KST,
    )

    private fun newDeliveryJob() = OwnerAlertDeliveryJob(alertStore, mailSender, discordSender, newExecutor(), clock)

    private fun cycle(signalJob: OwnerMarketSignalJob) {
        assertThat(signalJob.run()).isEqualTo(JobResult.Success)
        assertThat(newDeliveryJob().run()).isEqualTo(JobResult.Success)
    }

    @Test
    fun `(a)(b) 분봉 저점 대비 급등은 한 번 SENT 되고 10분 뒤에는 쿨다운이 억제한다`() {
        insertMinutes(START.minus(Duration.ofHours(1)), 60, high = "3.00", low = "2.00", close = "2.00")
        current.rate = BigDecimal("2.60")
        val signalJob = newSignalJob()

        // (a)
        cycle(signalJob)

        val first = rows()
        assertThat(first).hasSize(1)
        assertThat(first.single()["event_key"] as String)
            .isEqualTo("owner-market.btc-bithumb-binance.swing-up.${START.toEpochMilli()}")
        assertThat(first.single()["channel"]).isEqualTo("DISCORD")
        assertThat(first.single()["status"]).isEqualTo("SENT")
        assertThat(discordSender.sent.map { it.first })
            .containsExactly(DiscordRoute(DiscordTarget.MARKET, DiscordPush.SOUND))
        assertThat(mailSender.sent).isEmpty()
        val notifiedAt = lastNotifiedAt(SWING_UP)
        assertThat(notifiedAt).isNotNull()

        // (b) 새 clock 기준으로 분 bucket 을 다시 적재 - 저점 2.00 유지, 급변 창 10개 모두 존재, 현재 2.60 (근거는 충분)
        clock.advance(Duration.ofMinutes(10))
        insertMinutes(START, 10, high = "3.00", low = "2.00", close = "2.00")
        cycle(signalJob)

        assertThat(rows()).hasSize(1)
        assertThat(discordSender.sent).hasSize(1)
        assertThat(lastNotifiedAt(SWING_UP)).isEqualTo(notifiedAt)
    }

    @Test
    fun `(c) 24시간 신고가는 무음 Discord 로 한 번 SENT 된다`() {
        insertHours(START.minus(Duration.ofHours(24)), 23, high = "2.50", low = "2.40", close = "2.50")
        insertMinutes(START.minus(Duration.ofHours(1)), 60, high = "2.50", low = "2.40", close = "2.50")
        current.rate = BigDecimal("2.70")

        cycle(newSignalJob())

        val sent = rows()
        assertThat(sent).hasSize(1)
        assertThat(sent.single()["event_key"] as String)
            .isEqualTo("owner-market.btc-bithumb-binance.extreme-24h-high.${START.toEpochMilli()}")
        assertThat(sent.single()["status"]).isEqualTo("SENT")
        assertThat(discordSender.sent.map { it.first })
            .containsExactly(DiscordRoute(DiscordTarget.MARKET, DiscordPush.SILENT))
    }

    @Test
    fun `(d)(e)(f) 09시 요약은 전일 행으로 메일과 Discord 에 한 번 가고 재시작해도 늘지 않는다`() {
        insertDay("2026-10-28", high = "2.20", low = "0.90", open = "1.10", close = "1.50", avg = "1.30", fxRate = "1390.25")
        // (f) 오늘 날짜 행은 값을 다르게 넣어 읽히지 않음을 가린다
        insertDay("2026-10-29", high = "9.99", low = "8.88", open = "9.11", close = "9.55", avg = "9.33", fxRate = "1999.99")
        pnlLatest.record(AccountPnlLatest(true, BigDecimal("1.23"), BigDecimal("12345"), START.minus(Duration.ofMinutes(5))))

        cycle(newSignalJob(MarketAlertSettings()))

        val eventKey = "owner-market.btc-bithumb-binance.daily-summary.2026-10-28"
        val summary = rows()
        assertThat(summary).hasSize(2)
        assertThat(summary.map { it["event_key"] }).containsOnly(eventKey)
        assertThat(summary.map { it["channel"] }).containsExactlyInAnyOrder("EMAIL", "DISCORD")
        assertThat(summary.map { it["status"] }).containsOnly("SENT")
        val mail = mailSender.sent.single()
        assertThat(mail.eventKey).isEqualTo(eventKey)
        assertThat(mail.body)
            .contains("환율(USD/KRW)")
            .contains("실계정 순수익률: +")
            .contains("+2.20%")
            .contains("1,390.25")
            .doesNotContain("9.99")
            .doesNotContain("1,999.99")
        assertThat(discordSender.sent.map { it.first })
            .containsExactly(DiscordRoute(DiscordTarget.MARKET, DiscordPush.SILENT))
        assertThat(discordSender.sent.single().second.description).contains("환율(USD/KRW)").doesNotContain("9.99")
        assertThat(
            jdbcTemplate.queryForObject(
                "SELECT summary_date FROM owner_market_alert_state WHERE signal_key = ?",
                Date::class.java,
                SUMMARY,
            ).toString(),
        ).isEqualTo("2026-10-29")

        // (e) 재시작 - 새 Job 인스턴스가 같은 날 회차를 돌려도 요약 행은 늘지 않는다
        clock.advance(Duration.ofMinutes(30))
        cycle(newSignalJob(MarketAlertSettings()))

        assertThat(rows()).hasSize(2)
        assertThat(mailSender.sent).hasSize(1)
        assertThat(discordSender.sent).hasSize(1)
    }

    // ---- 도우미 ----

    private fun rows(): List<Map<String, Any?>> =
        jdbcTemplate.queryForList("SELECT * FROM account_pnl_mail ORDER BY id")

    private fun lastNotifiedAt(signalKey: String): Timestamp? = jdbcTemplate.queryForObject(
        "SELECT last_notified_at FROM owner_market_alert_state WHERE signal_key = ?",
        Timestamp::class.java,
        signalKey,
    )

    private fun insertMinutes(from: Instant, count: Int, high: String, low: String, close: String) =
        insertBuckets("premium_minute", "minute_at", from, count, Duration.ofMinutes(1), high, low, close)

    private fun insertHours(from: Instant, count: Int, high: String, low: String, close: String) =
        insertBuckets("premium_hour", "hour_at", from, count, Duration.ofHours(1), high, low, close)

    @Suppress("LongParameterList")
    private fun insertBuckets(
        table: String,
        column: String,
        from: Instant,
        count: Int,
        step: Duration,
        high: String,
        low: String,
        close: String,
    ) {
        repeat(count) { index ->
            val at = UTC_FORMAT.format(from.plus(step.multipliedBy(index.toLong())))
            jdbcTemplate.update(
                "INSERT INTO $table (symbol, korea_exchange, foreign_exchange, $column, high, low, open, close, avg, " +
                    "count, created_at, fx_rate) VALUES ('BTC', 'BITHUMB', 'BINANCE', ?, ?, ?, ?, ?, ?, 60, ?, 1400.0000)",
                at, BigDecimal(high), BigDecimal(low), BigDecimal(close), BigDecimal(close), BigDecimal(close), at,
            )
        }
    }

    @Suppress("LongParameterList")
    private fun insertDay(
        date: String,
        high: String,
        low: String,
        open: String,
        close: String,
        avg: String,
        fxRate: String,
    ) {
        jdbcTemplate.update(
            "INSERT INTO premium_day (symbol, korea_exchange, foreign_exchange, day_at, high, low, open, close, avg, " +
                "count, created_at, fx_rate) VALUES ('BTC', 'BITHUMB', 'BINANCE', ?, ?, ?, ?, ?, ?, 1440, ?, ?)",
            date, BigDecimal(high), BigDecimal(low), BigDecimal(open), BigDecimal(close), BigDecimal(avg),
            UTC_FORMAT.format(START), BigDecimal(fxRate),
        )
    }

    private companion object {
        val START: Instant = Instant.parse("2026-10-29T00:00:00Z")
        val KST: ZoneId = ZoneId.of("Asia/Seoul")
        val UTC_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC)
        const val SWING_UP = "market.btc-bithumb-binance.swing-up"
        const val SUMMARY = "market.btc-bithumb-binance.daily-summary"

        /** 시작 시각이 09:00 KST 라 요약이 끼지 않도록 요약 시각을 늦춘 설정. */
        val QUIET_SUMMARY = MarketAlertSettings(summaryHour = 23)
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

    /** 호출 시점 `clock - 1초` 에 관측된 현재 김프. */
    class FakeCurrent(private val clock: Clock) : PremiumReadPort {
        @Volatile var rate: BigDecimal = BigDecimal("1.00")

        override fun findLatest(pair: MarketPair): PremiumSnapshot {
            val observed = clock.instant().minus(Duration.ofSeconds(1))
            return PremiumSnapshot(
                pair, rate, BigDecimal("140000000"), BigDecimal("100000"), BigDecimal("140000000"),
                BigDecimal("1400.00"), observed,
            )
        }
    }

    /** 호출 시점 `clock - 1분` 환율. */
    class FakeFx(private val clock: Clock) : FxRateReadPort {
        override fun findLatest(base: Currency, quote: Currency) = ExchangeRateSnapshot(
            base.code,
            quote.code,
            BigDecimal("1400.50"),
            clock.instant().minus(Duration.ofMinutes(1)),
        )
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
