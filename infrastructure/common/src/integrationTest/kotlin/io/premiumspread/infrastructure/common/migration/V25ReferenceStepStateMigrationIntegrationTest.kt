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

/** `V25` — 기준 포지션 단계 상태(#131) 6열과 제약. 기존 행은 새 열 NULL 로 보존돼야 한다. */
@Tag("integration")
@Testcontainers
class V25ReferenceStepStateMigrationIntegrationTest {

    @Test
    fun `V24 에서 latest 로 올려도 기존 값이 보존되고 새 열은 NULL 이다`() {
        val v24 = flyway(target = MigrationVersion.fromVersion("24"))
        v24.clean()
        v24.migrate()
        assertThat(columns()).doesNotContain(*NEW_COLUMNS)
        exec(
            "INSERT INTO account_pnl_monitor_state (id, in_episode, outage_notified, version, updated_at, " +
                "korea_quantity, korea_average_buy_price, foreign_signed_quantity, foreign_entry_price) " +
                "VALUES (1, TRUE, FALSE, 7, '2026-10-23 09:00:02.000000', '0.2', '100', '-0.2', '70')",
        )
        val before = snapshot()

        assertThat(flyway().migrate().success).isTrue()

        assertThat(appliedVersions()).contains("25")
        assertThat(columns()).contains(*NEW_COLUMNS)
        assertThat(snapshot()).isEqualTo(before)
        assertThat(
            query(
                "SELECT COUNT(*) FROM account_pnl_monitor_state WHERE reference_korea_quantity IS NULL " +
                    "AND reference_korea_price IS NULL AND reference_foreign_quantity IS NULL " +
                    "AND reference_foreign_price IS NULL AND reference_step_phase IS NULL " +
                    "AND reference_step_level IS NULL",
            ) { it.getInt(1) },
        ).containsExactly(1)
    }

    @Test
    fun `6열이 전부 NULL 이거나 전부 채워진 경우만 허용한다`() {
        val flyway = flyway()
        flyway.clean()
        assertThat(flyway.migrate().success).isTrue()
        exec(
            "INSERT INTO account_pnl_monitor_state (id, in_episode, outage_notified, version, updated_at) " +
                "VALUES (1, FALSE, FALSE, 1, '2026-10-23 09:00:00.000000')",
        )

        exec(
            "UPDATE account_pnl_monitor_state SET reference_korea_quantity = '1', reference_korea_price = '2', " +
                "reference_foreign_quantity = '1', reference_foreign_price = '3', " +
                "reference_step_phase = 'FLAT', reference_step_level = -2",
        )
        assertThatThrownBy { exec("UPDATE account_pnl_monitor_state SET reference_step_level = NULL") }
            .isInstanceOf(SQLException::class.java)
            .satisfies({ e -> assertThat(e.message).contains("ck_account_pnl_monitor_state_reference_step") })
        assertThatThrownBy { exec("UPDATE account_pnl_monitor_state SET reference_step_phase = 'BAD'") }
            .isInstanceOf(SQLException::class.java)
            .satisfies({ e -> assertThat(e.message).contains("ck_account_pnl_monitor_state_reference_step") })
    }

    private fun snapshot(): List<String> =
        query(
            "SELECT CONCAT_WS('|', id, in_episode, outage_notified, version, updated_at, korea_quantity, " +
                "korea_average_buy_price, foreign_signed_quantity, foreign_entry_price) FROM account_pnl_monitor_state",
        ) { it.getString(1) }

    private fun exec(sql: String) {
        connection().use { c -> c.createStatement().use { it.executeUpdate(sql) } }
    }

    private fun columns(): List<String> =
        query(
            "SELECT COLUMN_NAME FROM information_schema.COLUMNS " +
                "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'account_pnl_monitor_state'",
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
            "reference_korea_quantity",
            "reference_korea_price",
            "reference_foreign_quantity",
            "reference_foreign_price",
            "reference_step_phase",
            "reference_step_level",
        )

        @Container
        @JvmStatic
        val mysql: MySQLContainer<*> = MySQLContainer("mysql:8.0")
            .withDatabaseName("premiumspread_reference_step_migration")
            .withUsername("test")
            .withPassword("test")
    }
}
