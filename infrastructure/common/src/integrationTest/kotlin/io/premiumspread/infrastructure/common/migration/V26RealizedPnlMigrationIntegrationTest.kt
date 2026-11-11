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

/** `V26` — 확정 손익(#130) 정산 열 15개와 상태·실패 범주 CHECK. 기존 건 행은 새 열 NULL 로 보존돼야 한다. */
@Tag("integration")
@Testcontainers
class V26RealizedPnlMigrationIntegrationTest {

    @Test
    fun `V25 상태의 기존 건 행에 V26 을 적용하면 새 열이 모두 NULL 이고 기존 열 값은 그대로다`() {
        val v25 = flyway(target = MigrationVersion.fromVersion("25"))
        v25.clean()
        v25.migrate()
        assertThat(columns()).doesNotContain(*NEW_COLUMNS)
        insertEpisode("CLOSED", "'2026-10-23 10:00:00.000000'")
        insertEpisode("OPEN", null)
        val before = snapshot()

        assertThat(flyway().migrate().success).isTrue()

        assertThat(appliedVersions()).contains("26")
        assertThat(columns()).contains(*NEW_COLUMNS)
        assertThat(snapshot()).isEqualTo(before)
        assertThat(
            query(
                "SELECT COUNT(*) FROM account_position_episode WHERE " +
                    NEW_COLUMNS.joinToString(" AND ") { "$it IS NULL" },
            ) { it.getInt(1) },
        ).containsExactly(2)
    }

    @Test
    fun `상태 열에는 PENDING CONFIRMED MANUAL 밖의 값을 넣을 수 없다`() {
        val flyway = flyway()
        flyway.clean()
        assertThat(flyway.migrate().success).isTrue()
        insertEpisode("CLOSED", "'2026-10-23 10:00:00.000000'")

        exec(
            "UPDATE account_position_episode SET korea_settlement = 'CONFIRMED', foreign_settlement = 'MANUAL', " +
                "settlement_failure = 'WINDOW_LIMIT'",
        )
        exec("UPDATE account_position_episode SET korea_settlement = 'PENDING', foreign_settlement = NULL")
        assertThatThrownBy { exec("UPDATE account_position_episode SET korea_settlement = 'SETTLED'") }
            .isInstanceOf(SQLException::class.java)
            .satisfies({ e -> assertThat(e.message).contains("ck_account_position_episode_settlement_status") })
        assertThatThrownBy { exec("UPDATE account_position_episode SET foreign_settlement = 'BAD'") }
            .isInstanceOf(SQLException::class.java)
            .satisfies({ e -> assertThat(e.message).contains("ck_account_position_episode_settlement_status") })
        assertThatThrownBy { exec("UPDATE account_position_episode SET settlement_failure = 'TIMEOUT'") }
            .isInstanceOf(SQLException::class.java)
            .satisfies({ e -> assertThat(e.message).contains("ck_account_position_episode_settlement_failure") })
    }

    private fun insertEpisode(status: String, closedAt: String?) {
        exec(
            "INSERT INTO account_position_episode (status, opened_at, closed_at, korea_quantity, " +
                "korea_average_price, foreign_signed_quantity, foreign_entry_price, reference_korea_quantity, " +
                "reference_korea_price, reference_foreign_quantity, reference_foreign_price, position_source, " +
                "event_count, created_at, updated_at) VALUES ('$status', '2026-10-23 09:00:00.000000', " +
                "${closedAt ?: "NULL"}, '0.2', '100', '-0.2', '70', '0.2', '100', '0.2', '70', 'MANUAL_ENV', 3, " +
                "'2026-10-23 09:00:00.000000', '2026-10-23 09:00:01.000000')",
        )
    }

    private fun snapshot(): List<String> =
        query(
            "SELECT CONCAT_WS('|', id, status, opened_at, closed_at, korea_quantity, korea_average_price, " +
                "foreign_signed_quantity, foreign_entry_price, reference_korea_quantity, reference_korea_price, " +
                "reference_foreign_quantity, reference_foreign_price, position_source, event_count, open_marker, " +
                "created_at, updated_at) FROM account_position_episode ORDER BY id",
        ) { it.getString(1) }

    private fun exec(sql: String) {
        connection().use { c -> c.createStatement().use { it.executeUpdate(sql) } }
    }

    private fun columns(): List<String> =
        query(
            "SELECT COLUMN_NAME FROM information_schema.COLUMNS " +
                "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'account_position_episode'",
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
        private val NEW_COLUMNS = arrayOf(
            "korea_settlement",
            "foreign_settlement",
            "korea_realized_krw",
            "foreign_realized_pnl_usdt",
            "foreign_commission_usdt",
            "foreign_funding_usdt",
            "fx_rate",
            "fx_observed_at",
            "total_realized_krw",
            "settled_seq",
            "settled_at",
            "settlement_attempts",
            "settlement_failure",
            "settlement_next_at",
            "settlement_target_seq",
        )

        @Container
        @JvmStatic
        val mysql: MySQLContainer<*> = MySQLContainer("mysql:8.0")
            .withDatabaseName("premiumspread_realized_pnl_migration")
            .withUsername("test")
            .withPassword("test")
    }
}
