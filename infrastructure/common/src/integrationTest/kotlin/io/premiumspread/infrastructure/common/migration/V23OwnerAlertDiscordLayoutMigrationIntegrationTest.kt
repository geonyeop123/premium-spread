package io.premiumspread.infrastructure.common.migration

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Types

/** `V23` — owner 알림(#102) Discord embed 레이아웃 열 `account_pnl_mail.discord_layout` 과 CHECK. */
@Tag("integration")
@Testcontainers
class V23OwnerAlertDiscordLayoutMigrationIntegrationTest {

    @Test
    fun `빈 DB 에서 latest 는 V23 까지 성공하고 마지막 열이 discord_layout 이다`() {
        val flyway = flyway()
        flyway.clean()

        assertThat(flyway.migrate().success).isTrue()

        assertThat(appliedVersions()).containsAll((1..23).map(Int::toString))
        assertThat(columns().last()).isEqualTo("discord_layout:text:YES")
        assertThat(columns()).hasSize(16)
    }

    @Test
    fun `V22 에서 V23 으로 올려도 기존 EMAIL 과 DISCORD 행이 그대로고 layout 은 NULL 이다`() {
        val v22 = flyway(target = MigrationVersion.fromVersion("22"))
        v22.clean()
        v22.migrate()
        assertThat(columns()).doesNotContain("discord_layout:text:YES")
        exec(
            "INSERT INTO account_pnl_mail (event_key, subject, body, status, attempt_count, created_at, channel) " +
                "VALUES ('evt-1', 'subject', 'body', 'PENDING', 2, '2026-10-23 09:00:00.000000', 'EMAIL')",
        )
        exec(
            "INSERT INTO account_pnl_mail (event_key, subject, body, status, attempt_count, created_at, channel, " +
                "discord_target, discord_push, discord_color) VALUES ('evt-2', 'sub2', 'body2', 'PENDING', 0, " +
                "'2026-10-23 09:00:00.000000', 'DISCORD', 'OWNER', 'SOUND', 65280)",
        )
        val before = rows()
        assertThat(before).hasSize(2)

        assertThat(flyway().migrate().success).isTrue()

        assertThat(rows()).isEqualTo(before)
        assertThat(query("SELECT discord_layout FROM account_pnl_mail ORDER BY id") { it.getString(1) })
            .containsExactly(null, null)
    }

    @Test
    fun `CHECK 는 EMAIL 행의 layout 값을 거부하고 DISCORD 행의 NULL 과 값을 허용한다`() {
        val flyway = flyway()
        flyway.clean()
        assertThat(flyway.migrate().success).isTrue()

        assertThatThrownBy { insert("e1", "EMAIL", "{\"v\":1}") }
            .isInstanceOf(SQLException::class.java)
            .satisfies({ e ->
                assertThat((e as SQLException).errorCode).isEqualTo(3819)
                assertThat(e.message).contains("ck_account_pnl_mail_discord_layout")
            })
        insert("e2", "EMAIL", null)
        insert("d1", "DISCORD", null)
        insert("d2", "DISCORD", "{\"v\":1}")

        assertThat(query("SELECT COUNT(*) FROM account_pnl_mail") { it.getLong(1) }).containsExactly(3L)
    }

    private fun insert(key: String, channel: String, layout: String?) {
        val discord = channel == "DISCORD"
        connection().use { c ->
            c.prepareStatement(
                "INSERT INTO account_pnl_mail (event_key, subject, body, status, attempt_count, created_at, channel, " +
                    "discord_target, discord_push, discord_color, discord_layout) " +
                    "VALUES (?, 's', 'b', 'PENDING', 0, '2026-10-23 09:00:00.000000', ?, ?, ?, ?, ?)",
            ).use { ps ->
                ps.setString(1, key)
                ps.setString(2, channel)
                ps.setString(3, if (discord) "OWNER" else null)
                ps.setString(4, if (discord) "SOUND" else null)
                if (discord) ps.setInt(5, 1) else ps.setNull(5, Types.INTEGER)
                ps.setString(6, layout)
                ps.executeUpdate()
            }
        }
    }

    private fun exec(sql: String) {
        connection().use { c -> c.createStatement().use { it.executeUpdate(sql) } }
    }

    private fun rows(): List<String> =
        query(
            "SELECT event_key, subject, body, status, attempt_count, channel, discord_target, discord_push, " +
                "discord_color, created_at FROM account_pnl_mail ORDER BY id",
        ) { r -> (1..10).joinToString("|") { i -> "${r.getString(i)}" } }

    private fun columns(): List<String> =
        query(
            "SELECT CONCAT(COLUMN_NAME, ':', DATA_TYPE, ':', IS_NULLABLE) FROM information_schema.COLUMNS " +
                "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'account_pnl_mail' ORDER BY ORDINAL_POSITION",
        ) { it.getString(1) }

    private fun <T> query(sql: String, map: (ResultSet) -> T): List<T> =
        connection().use { c ->
            c.createStatement().use { s ->
                s.executeQuery(sql).use { r -> buildList { while (r.next()) add(map(r)) } }
            }
        }

    private fun flyway(target: MigrationVersion? = null): Flyway {
        val configuration = Flyway.configure()
            .dataSource(jdbcUrl, mysql.username, mysql.password)
            .locations("classpath:db/migration")
            .cleanDisabled(false)
        if (target != null) configuration.target(target)
        return configuration.load()
    }

    private fun appliedVersions(): List<String> =
        query("SELECT version FROM flyway_schema_history WHERE success = 1 ORDER BY installed_rank") { it.getString(1) }

    private fun connection() = DriverManager.getConnection(jdbcUrl, mysql.username, mysql.password)

    private val jdbcUrl: String
        get() = mysql.jdbcUrl + if (mysql.jdbcUrl.contains('?')) "&sslMode=DISABLED" else "?sslMode=DISABLED"

    companion object {
        @Container
        @JvmStatic
        val mysql: MySQLContainer<*> = MySQLContainer("mysql:8.0")
            .withDatabaseName("premiumspread_discord_layout_migration")
            .withUsername("test")
            .withPassword("test")
    }
}
