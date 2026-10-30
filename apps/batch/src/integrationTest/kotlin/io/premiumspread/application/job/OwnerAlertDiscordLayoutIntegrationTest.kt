package io.premiumspread.application.job

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.premiumspread.application.common.JobExecutor
import io.premiumspread.application.common.JobResult
import io.premiumspread.application.job.ownermarket.OwnerMarketSignalJob
import io.premiumspread.application.notification.owneralert.OwnerAlertDeliveryJob
import io.premiumspread.domain.accountpnl.AccountPnlAlertStore
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
import io.premiumspread.domain.owneralert.OwnerAlertChannels
import io.premiumspread.domain.owneralert.OwnerAlertKind
import io.premiumspread.domain.owneralert.OwnerAlertRouteOverride
import io.premiumspread.domain.owneralert.OwnerAlertRouting
import io.premiumspread.domain.ownermarket.MarketAlertSettings
import io.premiumspread.domain.ownermarket.OwnerMarketAlertStore
import io.premiumspread.domain.premium.PremiumReadPort
import io.premiumspread.domain.premium.PremiumSnapshot
import io.premiumspread.domain.ticker.Currency
import io.premiumspread.domain.ticker.Quote
import io.premiumspread.domain.ticker.Symbol
import io.premiumspread.infrastructure.batch.notification.DiscordWebhookSenderAdapter
import io.premiumspread.infrastructure.batch.notification.DiscordWebhooks
import io.premiumspread.redis.AggregationTimeUnit
import io.premiumspread.redis.RedisKeyGenerator
import io.premiumspread.support.BatchIntegrationTestBase
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Collections

/**
 * AC16 - 시장 알림 레이아웃 E2E. 실제 MySQL outbox + 실제 [DiscordWebhookSenderAdapter] + local MockWebServer.
 * 실제 webhook 주소는 쓰지 않는다. 메일 sender 만 기록용 fake 다.
 */
class OwnerAlertDiscordLayoutIntegrationTest : BatchIntegrationTestBase() {

    @Autowired private lateinit var marketStore: OwnerMarketAlertStore

    @Autowired private lateinit var alertStore: AccountPnlAlertStore

    @Autowired private lateinit var pnlLatest: AccountPnlLatestStore

    @Autowired private lateinit var aggregations: PremiumAggregationReadPort

    private val clock = FixedClock(START)
    private val mailSender = RecordingMailSender()
    private val executors = mutableListOf<JobExecutor>()
    private val server = MockWebServer()
    private val mapper = ObjectMapper()

    @BeforeEach
    fun prepare() {
        server.start()
        listOf(
            "owner_market_alert_state",
            "account_pnl_latest",
            "account_pnl_mail",
            "premium_minute",
            "premium_hour",
            "premium_day",
        ).forEach { jdbcTemplate.execute("TRUNCATE TABLE `$it`") }
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
        runCatching { server.shutdown() }
    }

    private fun newExecutor() = JobExecutor(AlwaysLock, NoopRecorder, NoopAlert, clock).also { executors += it }

    private fun signalJob(routing: OwnerAlertRouting) = OwnerMarketSignalJob(
        FakeCurrent(clock),
        aggregations,
        FakeFx(clock),
        pnlLatest,
        { MARKET },
        marketStore,
        routing,
        newExecutor(),
        clock,
        MarketAlertSettings(summaryHour = 23),
        KST,
    )

    private fun deliveryJob(): OwnerAlertDeliveryJob {
        val webhooks = DiscordWebhooks(
            pnl = null,
            market = server.url("/api/webhooks/2/TOKENmarketQ7").toUri(),
            ops = null,
        )
        val discord = DiscordWebhookSenderAdapter(webhooks, Duration.ofSeconds(3), Duration.ofSeconds(2)) { }
        return OwnerAlertDeliveryJob(alertStore, mailSender, discord, newExecutor(), clock)
    }

    private fun swingCycle(routing: OwnerAlertRouting) {
        insertMinutes(START.minus(Duration.ofHours(1)), 60)
        assertThat(signalJob(routing).run()).isEqualTo(JobResult.Success)
        assertThat(deliveryJob().run()).isEqualTo(JobResult.Success)
    }

    @Test
    fun `(a) 시장 급등은 author·칸 3개·footer·timestamp 를 가진 embed 로 가고 행이 SENT 이며 레이아웃이 저장된다`() {
        server.enqueue(MockResponse().setResponseCode(204))

        swingCycle(OwnerAlertRouting(OwnerAlertChannels(email = false, discord = true)))

        assertThat(server.requestCount).isEqualTo(1)
        val embed = mapper.readTree(server.takeRequest().body.readUtf8())["embeds"][0]
        assertThat(embed["author"]["name"].asText()).isEqualTo("시장 · BTC 빗썸/바이낸스")
        assertThat(embed["fields"].map { it["name"].asText() }).containsExactly("현재 김프", "10분 저점", "변화")
        assertThat(embed["footer"]["text"].asText()).isNotBlank()
        assertThat(Instant.parse(embed["timestamp"].asText())).isEqualTo(START)
        assertThat(embed.has("description")).isFalse()

        val row = rows().single()
        assertThat(row["channel"]).isEqualTo("DISCORD")
        assertThat(row["status"]).isEqualTo("SENT")
        assertThat(row["discord_layout"]).isNotNull()
    }

    @Test
    fun `(b) 레이아웃이 NULL 인 옛 DISCORD 행은 제목과 설명 embed 로 간다`() {
        server.enqueue(MockResponse().setResponseCode(204))
        jdbcTemplate.update(
            "INSERT INTO account_pnl_mail (event_key, subject, body, status, attempt_count, created_at, channel, " +
                "discord_target, discord_push, discord_color, discord_layout) " +
                "VALUES ('legacy-1', 't', 'd', 'PENDING', 0, ?, 'DISCORD', 'MARKET', 'SOUND', 1, NULL)",
            Timestamp.from(START.minusSeconds(60)),
        )

        assertThat(deliveryJob().run()).isEqualTo(JobResult.Success)

        val embed: JsonNode = mapper.readTree(server.takeRequest().body.readUtf8())["embeds"][0]
        assertThat(embed.fieldNames().asSequence().toSet()).containsExactlyInAnyOrder("title", "description", "color")
        assertThat(rows().single()["status"]).isEqualTo("SENT")
    }

    @Test
    fun `(c) 메일을 켠 같은 회차의 EMAIL 행은 시장 접두 제목과 기준 시각 줄을 가진다`() {
        server.enqueue(MockResponse().setResponseCode(204))
        val routing = OwnerAlertRouting(
            OwnerAlertChannels(email = true, discord = true),
            mapOf(OwnerAlertKind.PREMIUM_SWING to OwnerAlertRouteOverride(email = true)),
        )

        swingCycle(routing)

        val email = rows().single { it["channel"] == "EMAIL" }
        assertThat(email["status"]).isEqualTo("SENT")
        assertThat(email["subject"] as String).startsWith("[시장] ")
        assertThat(email["body"] as String).contains("기준 시각 ")
        assertThat(mailSender.sent.single().subject).startsWith("[시장] ")
        assertThat(rows().single { it["channel"] == "DISCORD" }["status"]).isEqualTo("SENT")
    }

    // ---- 도우미 ----

    private fun rows(): List<Map<String, Any?>> = jdbcTemplate.queryForList("SELECT * FROM account_pnl_mail ORDER BY id")

    private fun insertMinutes(from: Instant, count: Int) {
        repeat(count) { index ->
            val at = UTC_FORMAT.format(from.plus(Duration.ofMinutes(index.toLong())))
            jdbcTemplate.update(
                "INSERT INTO premium_minute (symbol, korea_exchange, foreign_exchange, minute_at, high, low, open, " +
                    "close, avg, count, created_at, fx_rate) " +
                    "VALUES ('BTC', 'BITHUMB', 'BINANCE', ?, 3.00, 2.00, 2.00, 2.00, 2.00, 60, ?, 1400.0000)",
                at,
                at,
            )
        }
    }

    private companion object {
        val START: Instant = Instant.parse("2026-10-29T00:00:00Z")
        val KST: ZoneId = ZoneId.of("Asia/Seoul")
        val UTC_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC)
        val BTC = Symbol("BTC")
        val MARKET = BatchMarket(
            MarketPair.default(BTC),
            Quote.coin(BTC, Currency.KRW),
            Quote.coin(BTC, Currency.USD),
            Currency.USD,
            Currency.KRW,
        )
    }

    // integrationTest source set 은 test source set 의 fake 를 쓸 수 없어 필요한 최소분을 복제한다.

    class FixedClock(private val now: Instant) : Clock() {
        override fun instant(): Instant = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
    }

    /** 호출 시점 `clock - 1초` 에 관측된 현재 김프 2.60. */
    class FakeCurrent(private val clock: Clock) : PremiumReadPort {
        override fun findLatest(pair: MarketPair): PremiumSnapshot = PremiumSnapshot(
            pair,
            BigDecimal("2.60"),
            BigDecimal("140000000"),
            BigDecimal("100000"),
            BigDecimal("140000000"),
            BigDecimal("1400.00"),
            clock.instant().minus(Duration.ofSeconds(1)),
        )
    }

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
