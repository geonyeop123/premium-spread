package io.premiumspread.infrastructure.common.persistence.jdbc.accountpnl

import io.premiumspread.domain.accountpnl.AccountPnlMail
import io.premiumspread.domain.accountpnl.OwnerAlertIncident
import io.premiumspread.domain.accountpnl.OwnerAlertOutbox
import io.premiumspread.domain.accountpnl.OwnerAlertSignalState
import io.premiumspread.domain.owneralert.DiscordPush
import io.premiumspread.domain.owneralert.DiscordRoute
import io.premiumspread.domain.owneralert.DiscordTarget
import io.premiumspread.domain.owneralert.OwnerAlertChannel
import io.premiumspread.domain.owneralert.OwnerAlertKind
import io.premiumspread.infrastructure.common.CommonInfrastructureAutoConfiguration
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
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@Tag("integration")
class JdbcOwnerAlertOutboxIntegrationTest {

    private lateinit var jdbcTemplate: JdbcTemplate
    private lateinit var outbox: JdbcOwnerAlertOutbox
    private lateinit var alertStore: JdbcAccountPnlAlertStore

    @BeforeEach
    fun setUp() {
        migrateLatest()
        val dataSource = DriverManagerDataSource(jdbcUrl, mysql.username, mysql.password)
        val transactionManager = DataSourceTransactionManager(dataSource)
        jdbcTemplate = JdbcTemplate(dataSource)
        val clock = Clock.fixed(NOW, ZoneOffset.UTC)
        outbox = JdbcOwnerAlertOutbox(jdbcTemplate, transactionManager, clock)
        alertStore = JdbcAccountPnlAlertStore(jdbcTemplate, transactionManager, clock)
    }

    @Test
    fun `a 신규 신호 둘과 행 둘을 한 번에 넣고 version 1 로 읽는다`() {
        val incident = OwnerAlertIncident(STARTED, STARTED.plusSeconds(60), 2)
        val signals = listOf(signal("fx.ingestion", incident), signal("market.stream", null))

        val result = outbox.enqueue(signals, listOf(mail("e1"), mail("e2")))

        assertThat(result).isTrue()
        val loaded = outbox.loadSignals(listOf("fx.ingestion", "market.stream", "absent"))
        assertThat(loaded.keys).containsExactlyInAnyOrder("fx.ingestion", "market.stream")
        assertThat(loaded.getValue("fx.ingestion")).isEqualTo(signals[0].copy(version = 1))
        assertThat(loaded.getValue("market.stream")).isEqualTo(signals[1].copy(version = 1))
        assertThat(mailStatuses()).containsExactly("PENDING", "PENDING")
    }

    @Test
    fun `b 두 번째 신호 version 이 틀리면 false 이고 첫 신호와 행이 모두 기록되지 않는다`() {
        outbox.enqueue(listOf(signal("existing", null)), emptyList())

        val result = outbox.enqueue(
            listOf(signal("fresh", null), signal("existing", null, version = 7)),
            listOf(mail("e1")),
        )

        assertThat(result).isFalse()
        assertThat(outbox.loadSignals(listOf("fresh", "existing")).keys).containsExactly("existing")
        assertThat(outbox.loadSignals(listOf("existing")).getValue("existing").version).isEqualTo(1)
        assertThat(mailCount()).isZero()
    }

    @Test
    fun `c 이미 있는 key 를 version 0 으로 넣으면 false 이고 아무것도 기록되지 않는다`() {
        outbox.enqueue(listOf(signal("dup", null)), emptyList())

        val result = outbox.enqueue(listOf(signal("dup", ACTIVE)), listOf(mail("e1")))

        assertThat(result).isFalse()
        assertThat(outbox.loadSignals(listOf("dup")).getValue("dup")).isEqualTo(signal("dup", null, version = 1))
        assertThat(mailCount()).isZero()
    }

    @Test
    fun `d 신호가 없으면 행만 넣고 같은 eventKey 와 channel 재enqueue 는 행 수가 변하지 않는다`() {
        assertThat(outbox.enqueue(emptyList(), listOf(mail("e1")))).isTrue()
        assertThat(mailCount()).isEqualTo(1)

        assertThat(outbox.enqueue(emptyList(), listOf(mail("e1")))).isTrue()
        assertThat(mailCount()).isEqualTo(1)

        assertThat(outbox.enqueue(emptyList(), listOf(mail("e1", OwnerAlertChannel.DISCORD)))).isTrue()
        assertThat(mailCount()).isEqualTo(2)
    }

    @Test
    fun `d2 신호도 행도 없으면 true 이고 기록은 없다`() {
        assertThat(outbox.enqueue(emptyList(), emptyList())).isTrue()
        assertThat(mailCount()).isZero()
        assertThat(outbox.loadSignals(emptyList())).isEmpty()
    }

    @Test
    fun `e 복구 저장은 active 0 이고 incident 열이 NULL 과 0 이다`() {
        outbox.enqueue(listOf(signal("fx.ingestion", ACTIVE)), emptyList())

        val result = outbox.enqueue(listOf(signal("fx.ingestion", null, version = 1)), emptyList())

        assertThat(result).isTrue()
        val row = jdbcTemplate.queryForMap(
            "SELECT active, incident_started_at, last_notified_at, notify_count, version " +
                "FROM owner_alert_signal_state WHERE signal_key = 'fx.ingestion'",
        )
        assertThat(row["active"]).isIn(false, 0)
        assertThat(row["incident_started_at"]).isNull()
        assertThat(row["last_notified_at"]).isNull()
        assertThat((row["notify_count"] as Number).toInt()).isZero()
        assertThat((row["version"] as Number).toLong()).isEqualTo(2)
        assertThat(outbox.loadSignals(listOf("fx.ingestion")).getValue("fx.ingestion").incident).isNull()
    }

    @Test
    fun `f 256자 subject 행은 IllegalArgumentException 이고 DB 가 변하지 않는다`() {
        assertThatThrownBy {
            outbox.enqueue(listOf(signal("s1", null)), listOf(mail("ok"), mail("long", subject = "x".repeat(256))))
        }.isInstanceOf(IllegalArgumentException::class.java)

        assertThat(mailCount()).isZero()
        assertThat(outbox.loadSignals(listOf("s1"))).isEmpty()
    }

    @Test
    fun `f2 중복 signalKey 는 트랜잭션 전에 IllegalArgumentException 이고 DB 가 변하지 않는다`() {
        assertThatThrownBy {
            outbox.enqueue(listOf(signal("same", null), signal("same", ACTIVE)), listOf(mail("e1")))
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("signal keys must be distinct.")

        assertThat(mailCount()).isZero()
        assertThat(outbox.loadSignals(listOf("same"))).isEmpty()
    }

    @Test
    fun `g enqueue 한 행을 JdbcAccountPnlAlertStore claim 이 집고 markSent 한다`() {
        outbox.enqueue(listOf(signal("fx.ingestion", ACTIVE)), listOf(mail("owner-ops:1")))

        val claimed = alertStore.claim("owner-alert-delivery:test", 1, NOW)

        assertThat(claimed).hasSize(1)
        assertThat(claimed.single().mail.eventKey).isEqualTo("owner-ops:1")
        assertThat(alertStore.markSent(claimed.single().id, claimed.single().claimToken, NOW)).isTrue()
        assertThat(mailStatuses()).containsExactly("SENT")
    }

    @Test
    fun `i 실제 auto-configuration 컨텍스트에서 OwnerAlertOutbox bean 은 JdbcOwnerAlertOutbox 하나다`() {
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
                val beans = context.getBeansOfType(OwnerAlertOutbox::class.java)
                assertThat(beans).hasSize(1)
                assertThat(beans.values.single()).isInstanceOf(JdbcOwnerAlertOutbox::class.java)
            }
    }

    private fun signal(key: String, incident: OwnerAlertIncident?, version: Long = 0): OwnerAlertSignalState =
        OwnerAlertSignalState(key, OwnerAlertKind.FX_INGESTION_DELAYED, incident, version)

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
        private val STARTED = Instant.parse("2026-10-27T23:00:00Z")
        private val ACTIVE = OwnerAlertIncident(STARTED, STARTED, 1)

        val mysql: TestDatabase = SharedMySqlContainer.database("premiumspread_owner_alert")
    }
}
