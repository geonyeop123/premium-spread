package io.premiumspread.infrastructure.common.persistence.jdbc.accountpnl

import io.premiumspread.domain.accountpnl.AccountPnlMail
import io.premiumspread.domain.accountpnl.AccountPnlMonitorState
import io.premiumspread.domain.owneralert.DiscordField
import io.premiumspread.domain.owneralert.DiscordLayout
import io.premiumspread.domain.owneralert.DiscordPush
import io.premiumspread.domain.owneralert.DiscordRoute
import io.premiumspread.domain.owneralert.DiscordTarget
import io.premiumspread.domain.owneralert.OwnerAlertChannel
import io.premiumspread.infrastructure.common.persistence.jdbc.ownermarket.JdbcOwnerMarketAlertStore
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** `account_pnl_mail.discord_layout` 의 저장(세 store 공용 insert)·복원(claim)·손상 행 처리 (dod AC12). */
@Tag("integration")
@Testcontainers
@ExtendWith(OutputCaptureExtension::class)
class OwnerAlertDiscordLayoutStoreIntegrationTest {

    private lateinit var jdbcTemplate: JdbcTemplate
    private lateinit var alertStore: JdbcAccountPnlAlertStore
    private lateinit var outbox: JdbcOwnerAlertOutbox
    private lateinit var marketStore: JdbcOwnerMarketAlertStore

    @BeforeEach
    fun setUp() {
        val flyway = Flyway.configure()
            .dataSource(jdbcUrl, mysql.username, mysql.password)
            .locations("classpath:db/migration")
            .cleanDisabled(false)
            .load()
        flyway.clean()
        flyway.migrate()
        val dataSource = DriverManagerDataSource(jdbcUrl, mysql.username, mysql.password)
        val transactionManager = DataSourceTransactionManager(dataSource)
        jdbcTemplate = JdbcTemplate(dataSource)
        val clock = Clock.fixed(NOW, ZoneOffset.UTC)
        alertStore = JdbcAccountPnlAlertStore(jdbcTemplate, transactionManager, clock)
        outbox = JdbcOwnerAlertOutbox(jdbcTemplate, transactionManager, clock)
        marketStore = JdbcOwnerMarketAlertStore(jdbcTemplate, transactionManager, clock)
    }

    @Test
    fun `b 세 store 로 넣은 layout 이 claim 결과에서 같다`() {
        val l1 = layout("계정")
        val l2 = layout("운영")
        val l3 = layout("시장")
        assertThat(alertStore.save(0, AccountPnlMonitorState.INITIAL, listOf(discordMail("pnl", l1)))).isTrue()
        assertThat(outbox.enqueue(emptyList(), listOf(discordMail("ops", l2)))).isTrue()
        assertThat(marketStore.enqueue(emptyList(), listOf(discordMail("market", l3)))).isTrue()

        val claimed = alertStore.claim("owner:test", 10, NOW)

        assertThat(claimed.associate { it.mail.eventKey to it.mail.discordLayout })
            .isEqualTo(mapOf("pnl" to l1, "ops" to l2, "market" to l3))
    }

    @Test
    fun `b2 빈 fields 와 null 요소의 layout 도 같다`() {
        val empty = DiscordLayout(null, emptyList(), null, null)
        outbox.enqueue(emptyList(), listOf(discordMail("e", empty)))

        assertThat(alertStore.claim("owner:test", 10, NOW).single().mail.discordLayout).isEqualTo(empty)
    }

    @Test
    fun `c layout NULL 인 DISCORD 행과 EMAIL 행은 layout null 이다`() {
        outbox.enqueue(emptyList(), listOf(discordMail("d", null), emailMail("m")))

        val claimed = alertStore.claim("owner:test", 10, NOW)

        assertThat(claimed).hasSize(2)
        assertThat(claimed.map { it.mail.discordLayout }).containsOnlyNulls()
    }

    @Test
    fun `d 깨진 JSON 행은 예외 없이 layout null 로 돌려주고 WARN 에 id 와 reason 만 남긴다`(output: CapturedOutput) {
        outbox.enqueue(emptyList(), listOf(discordMail("bad", null), discordMail("good", layout("정상"))))
        jdbcTemplate.update("UPDATE account_pnl_mail SET discord_layout = '{bad' WHERE event_key = 'bad'")
        val badId = idOf("bad")

        val claimed = alertStore.claim("owner:test", 10, NOW)

        assertThat(claimed).hasSize(2)
        assertThat(claimed.single { it.mail.eventKey == "bad" }.mail.discordLayout).isNull()
        assertThat(claimed.single { it.mail.eventKey == "good" }.mail.discordLayout).isEqualTo(layout("정상"))
        assertThat(output.all)
            .contains("Owner alert discord layout unreadable; sending plain description: id=$badId, reason=json")
            .doesNotContain("{bad")
    }

    @Test
    fun `e JSON 은 읽히지만 DiscordMessage 조립이 실패하는 행은 layout null 이고 WARN reason shape 이다`(output: CapturedOutput) {
        // 칸 값 합계 5990 + subject 255 > 6000. layout 자체는 유효하다.
        val big = DiscordLayout(
            author = null,
            fields = (1..6).map { DiscordField("n$it", "v".repeat(995), true) },
            footer = null,
            timestamp = null,
        )
        outbox.enqueue(emptyList(), listOf(discordMail("big", null, subject = "s".repeat(255))))
        jdbcTemplate.update(
            "UPDATE account_pnl_mail SET discord_layout = ? WHERE event_key = 'big'",
            DiscordLayoutJson.write(big),
        )
        val id = idOf("big")

        val claimed = alertStore.claim("owner:test", 10, NOW)

        assertThat(claimed.single().mail.discordLayout).isNull()
        assertThat(output.all)
            .contains("Owner alert discord layout unreadable; sending plain description: id=$id, reason=shape")
    }

    @Test
    fun `e3 layout 없이도 조립이 실패하는 행은 shape 분류 없이 layout 을 그대로 두고 같은 배치의 shape 행만 null 이다`(output: CapturedOutput) {
        val big = DiscordLayout(
            author = null,
            fields = (1..6).map { DiscordField("n$it", "v".repeat(995), true) },
            footer = null,
            timestamp = null,
        )
        outbox.enqueue(
            emptyList(),
            listOf(
                discordMail("plainBad", layout("평문불량")),
                discordMail("shape", null, subject = "s".repeat(255)),
                discordMail("good", layout("정상")),
            ),
        )
        jdbcTemplate.update("UPDATE account_pnl_mail SET subject = ' ' WHERE event_key = 'plainBad'")
        jdbcTemplate.update(
            "UPDATE account_pnl_mail SET discord_layout = ? WHERE event_key = 'shape'",
            DiscordLayoutJson.write(big),
        )
        val shapeId = idOf("shape")

        val claimed = alertStore.claim("owner:test", 10, NOW).associate { it.mail.eventKey to it.mail.discordLayout }

        assertThat(claimed["plainBad"]).isEqualTo(layout("평문불량"))
        assertThat(claimed["shape"]).isNull()
        assertThat(claimed["good"]).isEqualTo(layout("정상"))
        assertThat(output.all)
            .contains("id=$shapeId, reason=shape")
            .doesNotContain("id=${idOf("plainBad")}, reason=shape")
    }

    @Test
    fun `e2 v 가 1 이 아닌 행은 reason version 이다`(output: CapturedOutput) {
        outbox.enqueue(emptyList(), listOf(discordMail("v2", null)))
        jdbcTemplate.update(
            "UPDATE account_pnl_mail SET discord_layout = ? WHERE event_key = 'v2'",
            """{"v":2,"author":null,"fields":[],"footer":null,"timestamp":null}""",
        )

        assertThat(alertStore.claim("owner:test", 10, NOW).single().mail.discordLayout).isNull()
        assertThat(output.all).contains("reason=version")
    }

    @Test
    fun `f 직렬화 결과가 65535 바이트를 넘으면 IllegalArgumentException 이고 DB 가 변하지 않는다`() {
        // 한글 3바이트 × 1024 × 25 = 76,800 바이트
        val huge = DiscordLayout(
            null,
            (1..25).map { DiscordField("n$it", "가".repeat(1024), true) },
            null,
            null,
        )

        assertThatThrownBy { outbox.enqueue(emptyList(), listOf(discordMail("ok", null), discordMail("huge", huge))) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("discordLayout must be at most 65535 bytes in UTF-8.")

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM account_pnl_mail", Int::class.java)).isZero()
    }

    @Test
    fun `g 직렬화 결과가 정확히 65535 바이트인 layout 은 저장된다`() {
        val exact = layoutOfSerializedBytes(65_535)
        assertThat(DiscordLayoutJson.write(exact).toByteArray(Charsets.UTF_8)).hasSize(65_535)

        assertThat(outbox.enqueue(emptyList(), listOf(discordMail("exact", exact)))).isTrue()

        // embed 합계 6000자 상한 때문에 claim 은 이 크기를 shape 로 거른다. 여기서는 TEXT 컬럼 경계 저장만 고정한다.
        assertThat(
            jdbcTemplate.queryForObject(
                "SELECT OCTET_LENGTH(discord_layout) FROM account_pnl_mail WHERE event_key = 'exact'",
                Int::class.java,
            ),
        ).isEqualTo(65_535)
    }

    /** 21 칸은 한글 1024자로 채우고, 22번째 칸의 한글 k 자 + ASCII a 자 조합으로 직렬화 크기를 정확히 [target] 에 맞춘다. */
    private fun layoutOfSerializedBytes(target: Int): DiscordLayout {
        val full = (1..21).map { DiscordField("n$it", "가".repeat(1024), true) }
        for (k in 0..1024) {
            for (a in 0..(1024 - k)) {
                if (k + a == 0) continue
                val candidate = DiscordLayout(null, full + DiscordField("last", "가".repeat(k) + "a".repeat(a), true), null, null)
                if (DiscordLayoutJson.write(candidate).toByteArray(Charsets.UTF_8).size == target) return candidate
            }
        }
        error("no layout serializes to exactly $target bytes")
    }

    private fun idOf(eventKey: String): Long =
        jdbcTemplate.queryForObject("SELECT id FROM account_pnl_mail WHERE event_key = ?", Long::class.java, eventKey)!!

    private fun layout(author: String) = DiscordLayout(
        author = author,
        fields = listOf(DiscordField("현김프", "1.78%", true), DiscordField("메모", "줄1\n\"줄2\" 😀", false)),
        footer = "환율 1,400.00원/USD",
        timestamp = Instant.parse("2026-10-20T00:00:00Z"),
    )

    private fun discordMail(eventKey: String, layout: DiscordLayout?, subject: String = "subject") = AccountPnlMail(
        eventKey = eventKey,
        subject = subject,
        body = "body",
        channel = OwnerAlertChannel.DISCORD,
        discordRoute = DiscordRoute(DiscordTarget.OPS, DiscordPush.SOUND),
        discordColor = 0xFF0000,
        discordLayout = layout,
    )

    private fun emailMail(eventKey: String) =
        AccountPnlMail(eventKey = eventKey, subject = "subject", body = "body", channel = OwnerAlertChannel.EMAIL)

    private val jdbcUrl: String
        get() = mysql.jdbcUrl + if (mysql.jdbcUrl.contains('?')) {
            "&sslMode=DISABLED&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true"
        } else {
            "?sslMode=DISABLED&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true"
        }

    companion object {
        private val NOW = Instant.parse("2026-10-28T00:00:00Z")

        @Container
        @JvmStatic
        val mysql: MySQLContainer<*> = MySQLContainer("mysql:8.0")
            .withDatabaseName("premiumspread_discord_layout_store")
            .withUsername("test")
            .withPassword("test")
    }
}
