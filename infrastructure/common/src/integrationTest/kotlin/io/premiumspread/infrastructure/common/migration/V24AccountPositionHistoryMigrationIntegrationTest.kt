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

/** `V24` — 실계정 포지션 이력(#129) 테이블 3개와 제약. 기존 account_pnl_* 행은 그대로여야 한다. */
@Tag("integration")
@Testcontainers
class V24AccountPositionHistoryMigrationIntegrationTest {

    @Test
    fun `V23 에서 latest 로 올려도 기존 행이 보존되고 새 테이블 3개가 생긴다`() {
        val v23 = flyway(target = MigrationVersion.fromVersion("23"))
        v23.clean()
        v23.migrate()
        assertThat(tables()).doesNotContain(*NEW_TABLES)
        exec(
            "INSERT INTO account_pnl_latest (id, has_position, net_percent, net_krw, calculated_at, updated_at) " +
                "VALUES (1, TRUE, 1.96, 12345, '2026-10-23 09:00:00.000000', '2026-10-23 09:00:01.000000')",
        )
        exec(
            "INSERT INTO account_pnl_monitor_state (id, in_episode, outage_notified, version, updated_at) " +
                "VALUES (1, TRUE, FALSE, 7, '2026-10-23 09:00:02.000000')",
        )
        val latestBefore = query("SELECT CONCAT_WS('|', id, has_position, net_percent, net_krw, calculated_at, updated_at) FROM account_pnl_latest") { it.getString(1) }
        val stateBefore = query("SELECT CONCAT_WS('|', id, in_episode, outage_notified, version, updated_at) FROM account_pnl_monitor_state") { it.getString(1) }

        assertThat(flyway().migrate().success).isTrue()

        assertThat(appliedVersions()).contains("24")
        assertThat(tables()).contains(*NEW_TABLES)
        assertThat(query("SELECT CONCAT_WS('|', id, has_position, net_percent, net_krw, calculated_at, updated_at) FROM account_pnl_latest") { it.getString(1) })
            .isEqualTo(latestBefore)
        assertThat(query("SELECT CONCAT_WS('|', id, in_episode, outage_notified, version, updated_at) FROM account_pnl_monitor_state") { it.getString(1) })
            .isEqualTo(stateBefore)
    }

    @Test
    fun `열린 건은 하나만 허용하고 관측은 건과 시각 조합이 유일하다`() {
        val flyway = flyway()
        flyway.clean()
        assertThat(flyway.migrate().success).isTrue()

        val open = insertEpisode("OPEN", null)
        assertThatThrownBy { insertEpisode("OPEN", null) }
            .isInstanceOf(SQLException::class.java)
            .satisfies({ e -> assertThat(e.message).contains("uk_account_position_episode_open") })
        insertEpisode("CLOSED", "'2026-10-23 10:00:00.000000'")
        insertEpisode("CLOSED", "'2026-10-23 11:00:00.000000'")

        insertObservation(open)
        assertThatThrownBy { insertObservation(open) }
            .isInstanceOf(SQLException::class.java)
            .satisfies({ e -> assertThat(e.message).contains("uk_account_pnl_observation_round") })
    }

    private fun insertEpisode(status: String, closedAt: String?): Long {
        connection().use { c ->
            c.createStatement().use { s ->
                s.executeUpdate(
                    "INSERT INTO account_position_episode (status, opened_at, closed_at, korea_quantity, " +
                        "korea_average_price, foreign_signed_quantity, foreign_entry_price, position_source, " +
                        "event_count, created_at, updated_at) VALUES ('$status', '2026-10-23 09:00:00.000000', " +
                        "${closedAt ?: "NULL"}, '0.2', '100', '-0.2', '70', 'EXCHANGE_API', 1, " +
                        "'2026-10-23 09:00:00.000000', '2026-10-23 09:00:00.000000')",
                    java.sql.Statement.RETURN_GENERATED_KEYS,
                )
                s.generatedKeys.use { k ->
                    k.next()
                    return k.getLong(1)
                }
            }
        }
    }

    private fun insertObservation(episodeId: Long) =
        exec(
            "INSERT INTO account_pnl_observation (episode_id, kind, observed_at, net_percent, net_krw, " +
                "current_premium_percent, entry_premium_percent, premium_gap_percent_point, fx_rate, " +
                "korea_exit_price, foreign_exit_price, created_at) VALUES ($episodeId, 'HOLDING', " +
                "'2026-10-23 09:01:00.000000', 1.0, 100, 1.0, 1.0, 0.0, 1400, 100, 70, '2026-10-23 09:01:00.000000')",
        )

    private fun exec(sql: String) {
        connection().use { c -> c.createStatement().use { it.executeUpdate(sql) } }
    }

    private fun tables(): List<String> =
        query("SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE()") { it.getString(1) }

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
        private val NEW_TABLES = arrayOf("account_position_episode", "account_position_event", "account_pnl_observation")

        @Container
        @JvmStatic
        val mysql: MySQLContainer<*> = MySQLContainer("mysql:8.0")
            .withDatabaseName("premiumspread_position_history_migration")
            .withUsername("test")
            .withPassword("test")
    }
}
