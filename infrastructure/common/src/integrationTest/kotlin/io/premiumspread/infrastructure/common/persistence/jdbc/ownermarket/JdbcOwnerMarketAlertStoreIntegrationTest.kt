package io.premiumspread.infrastructure.common.persistence.jdbc.ownermarket

import io.premiumspread.domain.accountpnl.AccountPnlLatestStore
import io.premiumspread.domain.accountpnl.AccountPnlMail
import io.premiumspread.domain.owneralert.DiscordPush
import io.premiumspread.domain.owneralert.DiscordRoute
import io.premiumspread.domain.owneralert.DiscordTarget
import io.premiumspread.domain.owneralert.OwnerAlertChannel
import io.premiumspread.domain.owneralert.OwnerAlertKind
import io.premiumspread.domain.ownermarket.MarketAlertState
import io.premiumspread.domain.ownermarket.OwnerMarketAlertStore
import io.premiumspread.domain.ownermarket.PremiumZone
import io.premiumspread.domain.ownermarket.ThresholdBaseline
import io.premiumspread.infrastructure.common.CommonInfrastructureAutoConfiguration
import io.premiumspread.infrastructure.common.persistence.jdbc.accountpnl.JdbcAccountPnlAlertStore
import io.premiumspread.infrastructure.common.persistence.jdbc.accountpnl.JdbcAccountPnlLatestStore
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import io.premiumspread.infrastructure.common.support.SharedMySqlContainer
import io.premiumspread.infrastructure.common.support.TestDatabase
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Tag("integration")
class JdbcOwnerMarketAlertStoreIntegrationTest {

    private lateinit var jdbcTemplate: JdbcTemplate
    private lateinit var store: JdbcOwnerMarketAlertStore
    private lateinit var alertStore: JdbcAccountPnlAlertStore

    @BeforeEach
    fun setUp() {
        migrateLatest()
        val dataSource = DriverManagerDataSource(jdbcUrl, mysql.username, mysql.password)
        val transactionManager = DataSourceTransactionManager(dataSource)
        jdbcTemplate = JdbcTemplate(dataSource)
        val clock = Clock.fixed(NOW, ZoneOffset.UTC)
        store = JdbcOwnerMarketAlertStore(jdbcTemplate, transactionManager, clock)
        alertStore = JdbcAccountPnlAlertStore(jdbcTemplate, transactionManager, clock)
    }

    @Test
    fun `a 상태 셋과 행 둘을 넣고 version 1 로 같은 값을 읽는다`() {
        val cooldown = cooldown("market.btc-bithumb-binance.swing-up")
        val threshold = threshold("market.btc-bithumb-binance.threshold")
        val summary = summary("market.btc-bithumb-binance.daily-summary")

        val result = store.enqueue(listOf(cooldown, threshold, summary), listOf(mail("e1"), mail("e2")))

        assertThat(result).isTrue()
        val loaded = store.load(listOf(cooldown.signalKey, threshold.signalKey, summary.signalKey, "absent"))
        assertThat(loaded.keys).containsExactlyInAnyOrder(cooldown.signalKey, threshold.signalKey, summary.signalKey)
        assertThat(loaded.getValue(cooldown.signalKey)).isEqualTo(cooldown.copy(version = 1))
        val loadedThreshold = loaded.getValue(threshold.signalKey)
        assertThat(loadedThreshold.version).isEqualTo(1)
        assertThat(loadedThreshold.zone).isEqualTo(PremiumZone.ABOVE)
        assertThat(loadedThreshold.baseline!!.sameAs(BASELINE)).isTrue()
        assertThat(loadedThreshold.baseline!!.upper).isEqualByComparingTo(BigDecimal("3.1234"))
        assertThat(loadedThreshold.baseline!!.lower).isNull()
        assertThat(loadedThreshold.lastNotifiedAt).isEqualTo(NOTIFIED)
        assertThat(loaded.getValue(summary.signalKey)).isEqualTo(summary.copy(version = 1))
        assertThat(loaded.getValue(summary.signalKey).summaryDate).isEqualTo(LocalDate.of(2026, 10, 27))
        assertThat(mailStatuses()).containsExactly("PENDING", "PENDING")
    }

    @Test
    fun `b 두 번째 상태 version 이 틀리면 false 이고 첫 상태와 행이 모두 기록되지 않는다`() {
        store.enqueue(listOf(cooldown("existing")), emptyList())

        val result = store.enqueue(
            listOf(cooldown("fresh"), cooldown("existing", version = 7)),
            listOf(mail("e1")),
        )

        assertThat(result).isFalse()
        assertThat(store.load(listOf("fresh", "existing")).keys).containsExactly("existing")
        assertThat(store.load(listOf("existing")).getValue("existing").version).isEqualTo(1)
        assertThat(mailCount()).isZero()
    }

    @Test
    fun `c 이미 있는 key 를 version 0 으로 넣으면 false 이고 아무것도 기록되지 않는다`() {
        store.enqueue(listOf(cooldown("dup")), emptyList())

        val result = store.enqueue(listOf(cooldown("dup", lastNotifiedAt = null)), listOf(mail("e1")))

        assertThat(result).isFalse()
        assertThat(store.load(listOf("dup")).getValue("dup")).isEqualTo(cooldown("dup", version = 1))
        assertThat(mailCount()).isZero()
    }

    @Test
    fun `d 상태가 없으면 행만 넣고 같은 eventKey 와 channel 재enqueue 는 행 수가 변하지 않는다`() {
        assertThat(store.enqueue(emptyList(), listOf(mail("e1")))).isTrue()
        assertThat(mailCount()).isEqualTo(1)

        assertThat(store.enqueue(emptyList(), listOf(mail("e1")))).isTrue()
        assertThat(mailCount()).isEqualTo(1)

        assertThat(store.enqueue(emptyList(), listOf(mail("e1", OwnerAlertChannel.DISCORD)))).isTrue()
        assertThat(mailCount()).isEqualTo(2)
    }

    @Test
    fun `d2 상태도 행도 없으면 true 이고 기록은 없다`() {
        assertThat(store.enqueue(emptyList(), emptyList())).isTrue()
        assertThat(mailCount()).isZero()
        assertThat(store.load(emptyList())).isEmpty()
    }

    @Test
    fun `e 행 없이 상태만 갱신하면 version 이 오르고 값이 바뀐다`() {
        store.enqueue(listOf(threshold("market.x.threshold")), emptyList())
        val later = NOTIFIED.plusSeconds(600)

        val result = store.enqueue(
            listOf(threshold("market.x.threshold", version = 1, zone = PremiumZone.BETWEEN, lastNotifiedAt = later)),
            emptyList(),
        )

        assertThat(result).isTrue()
        val loaded = store.load(listOf("market.x.threshold")).getValue("market.x.threshold")
        assertThat(loaded.version).isEqualTo(2)
        assertThat(loaded.zone).isEqualTo(PremiumZone.BETWEEN)
        assertThat(loaded.lastNotifiedAt).isEqualTo(later)
        assertThat(mailCount()).isZero()
    }

    @Test
    fun `f 256자 subject 행은 IllegalArgumentException 이고 DB 가 변하지 않는다`() {
        assertThatThrownBy {
            store.enqueue(listOf(cooldown("s1")), listOf(mail("ok"), mail("long", subject = "x".repeat(256))))
        }.isInstanceOf(IllegalArgumentException::class.java)

        assertThat(mailCount()).isZero()
        assertThat(store.load(listOf("s1"))).isEmpty()
    }

    @Test
    fun `f2 중복 signalKey 는 IllegalArgumentException 이고 DB 가 변하지 않는다`() {
        assertThatThrownBy {
            store.enqueue(listOf(cooldown("same"), cooldown("same")), listOf(mail("e1")))
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("signal keys must be distinct.")

        assertThat(mailCount()).isZero()
        assertThat(store.load(listOf("same"))).isEmpty()
    }

    @Test
    fun `f3 모르는 kind 와 zone 은 값 없는 메시지의 IllegalStateException 이다`() {
        store.enqueue(listOf(threshold("market.y.threshold")), emptyList())
        // 정상 DB 는 CHECK 로 모르는 zone 을 막는다. 손상 상황을 만들려고 이 테스트 DB 에서만 제약을 푼다.
        jdbcTemplate.execute("ALTER TABLE owner_market_alert_state DROP CHECK ck_owner_market_alert_state_zone")
        jdbcTemplate.update("UPDATE owner_market_alert_state SET premium_zone = 'SIDEWAYS'")
        assertThatThrownBy { store.load(listOf("market.y.threshold")) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("unknown premium zone in owner_market_alert_state")

        jdbcTemplate.update("UPDATE owner_market_alert_state SET alert_kind = 'NOPE'")
        assertThatThrownBy { store.load(listOf("market.y.threshold")) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("unknown owner alert kind in owner_market_alert_state")
    }

    @Test
    fun `g enqueue 한 행을 JdbcAccountPnlAlertStore claim 이 집는다`() {
        store.enqueue(listOf(cooldown("market.z.swing-up")), listOf(mail("owner-market:1")))

        val claimed = alertStore.claim("owner-alert-delivery:test", 1, NOW)

        assertThat(claimed).hasSize(1)
        assertThat(claimed.single().mail.eventKey).isEqualTo("owner-market:1")
    }

    @Test
    fun `j 같은 기존 상태를 두 스레드가 동시에 enqueue 하면 하나만 성공하고 행 1벌 version N+1 이다`() {
        store.enqueue(listOf(cooldown("market.race.swing-up")), emptyList())
        val existing = store.load(listOf("market.race.swing-up")).getValue("market.race.swing-up")
        assertThat(existing.version).isEqualTo(1)

        val results = enqueueConcurrently { index -> store.enqueue(listOf(existing), listOf(mail("race-existing-$index"))) }

        assertThat(results.count { it }).isEqualTo(1)
        assertThat(mailCount()).isEqualTo(1)
        assertThat(store.load(listOf(existing.signalKey)).getValue(existing.signalKey).version).isEqualTo(2)
    }

    @Test
    fun `k 새 key 를 두 스레드가 동시에 enqueue 하면 하나만 성공하고 행 1벌이다`() {
        val fresh = cooldown("market.race.fresh")

        val results = enqueueConcurrently { index -> store.enqueue(listOf(fresh), listOf(mail("race-fresh-$index"))) }

        assertThat(results.count { it }).isEqualTo(1)
        assertThat(mailCount()).isEqualTo(1)
        assertThat(store.load(listOf(fresh.signalKey)).getValue(fresh.signalKey).version).isEqualTo(1)
    }

    private fun enqueueConcurrently(action: (Int) -> Boolean): List<Boolean> {
        val executor = Executors.newFixedThreadPool(2)
        try {
            val barrier = CyclicBarrier(2)
            val futures = (0 until 2).map { index ->
                executor.submit<Boolean> {
                    barrier.await(30, TimeUnit.SECONDS)
                    action(index)
                }
            }
            return futures.map { it.get(60, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
            assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue()
        }
    }

    @Test
    fun `i 실제 auto-configuration 컨텍스트에서 두 store bean 이 각각 하나다`() {
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    DataSourceAutoConfiguration::class.java,
                    JdbcTemplateAutoConfiguration::class.java,
                    DataSourceTransactionManagerAutoConfiguration::class.java,
                    CommonInfrastructureAutoConfiguration::class.java,
                ),
            )
            .withBean(Clock::class.java, { Clock.fixed(NOW, ZoneOffset.UTC) })
            .withPropertyValues(
                "spring.datasource.url=$jdbcUrl",
                "spring.datasource.username=${mysql.username}",
                "spring.datasource.password=${mysql.password}",
            )
            .run { context ->
                assertThat(context).hasNotFailed()
                val markets = context.getBeansOfType(OwnerMarketAlertStore::class.java)
                assertThat(markets).hasSize(1)
                assertThat(markets.values.single()).isInstanceOf(JdbcOwnerMarketAlertStore::class.java)
                val latests = context.getBeansOfType(AccountPnlLatestStore::class.java)
                assertThat(latests).hasSize(1)
                assertThat(latests.values.single()).isInstanceOf(JdbcAccountPnlLatestStore::class.java)
            }
    }

    private fun cooldown(key: String, version: Long = 0, lastNotifiedAt: Instant? = NOTIFIED): MarketAlertState =
        MarketAlertState(key, OwnerAlertKind.PREMIUM_SWING, lastNotifiedAt, null, null, null, version)

    private fun threshold(
        key: String,
        version: Long = 0,
        zone: PremiumZone = PremiumZone.ABOVE,
        lastNotifiedAt: Instant? = NOTIFIED,
    ): MarketAlertState =
        MarketAlertState(key, OwnerAlertKind.PREMIUM_THRESHOLD_CROSSED, lastNotifiedAt, zone, BASELINE, null, version)

    private fun summary(key: String): MarketAlertState =
        MarketAlertState(key, OwnerAlertKind.DAILY_SUMMARY, NOTIFIED, null, null, LocalDate.of(2026, 10, 27), 0)

    private fun mail(
        eventKey: String,
        channel: OwnerAlertChannel = OwnerAlertChannel.EMAIL,
        subject: String = "subject",
    ): AccountPnlMail {
        val discord = channel == OwnerAlertChannel.DISCORD
        return AccountPnlMail(
            eventKey = eventKey,
            subject = subject,
            body = "body",
            channel = channel,
            discordRoute = if (discord) DiscordRoute(DiscordTarget.OPS, DiscordPush.SOUND) else null,
            discordColor = if (discord) 0xFF0000 else null,
        )
    }

    private fun mailCount(): Int = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM account_pnl_mail", Int::class.java)!!

    private fun mailStatuses(): List<String> =
        jdbcTemplate.queryForList("SELECT status FROM account_pnl_mail ORDER BY id", String::class.java)

    private fun migrateLatest() {
        val flyway = Flyway.configure()
            .dataSource(jdbcUrl, mysql.username, mysql.password)
            .locations("classpath:db/migration")
            .cleanDisabled(false)
            .load()
        flyway.clean()
        flyway.migrate()
    }

    private val jdbcUrl: String
        get() = mysql.jdbcUrl + if (mysql.jdbcUrl.contains('?')) {
            "&sslMode=DISABLED&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true"
        } else {
            "?sslMode=DISABLED&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true"
        }

    companion object {
        private val NOW = Instant.parse("2026-10-28T00:00:00Z")
        private val NOTIFIED = Instant.parse("2026-10-27T23:00:00Z")
        private val BASELINE = ThresholdBaseline(BigDecimal("3.1234"), null, BigDecimal("0.2000"))

        val mysql: TestDatabase = SharedMySqlContainer.database("premiumspread_owner_market")
    }
}
