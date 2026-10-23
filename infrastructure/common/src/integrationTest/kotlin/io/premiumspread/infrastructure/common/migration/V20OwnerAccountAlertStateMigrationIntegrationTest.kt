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
import java.sql.Timestamp

/**
 * `V20` — owner 계정 알림(#89) 상태 열 12개와 CHECK 3개.
 *
 * 구 binary(새 열을 모르는 상태 INSERT/UPDATE)가 V20 스키마에서도 안전한지 문자열 그대로 재생해 본다.
 */
@Tag("integration")
@Testcontainers
class V20OwnerAccountAlertStateMigrationIntegrationTest {

    @Test
    fun `빈 DB는 V20까지 migration 되고 상태 열 12개가 붙는다`() {
        val flyway = flyway()
        flyway.clean()

        assertThat(flyway.migrate().success).isTrue()

        assertThat(appliedVersions()).containsAll((1..20).map(Int::toString))
        assertThat(columns()).containsExactly(
            "id:tinyint:NO",
            "in_episode:tinyint:NO",
            "position_fingerprint:varchar:YES",
            "last_sent_at:datetime:YES",
            "last_success_at:datetime:YES",
            "monitoring_since:datetime:YES",
            "outage_notified:tinyint:NO",
            "version:bigint:NO",
            "updated_at:datetime:NO",
            "korea_quantity:varchar:YES",
            "korea_average_buy_price:varchar:YES",
            "foreign_signed_quantity:varchar:YES",
            "foreign_entry_price:varchar:YES",
            "hedge_mismatch_warned:tinyint:NO",
            "swing_bucket_start:datetime:YES",
            "swing_baseline_percent:decimal:YES",
            "swing_up_sent:tinyint:NO",
            "swing_down_sent:tinyint:NO",
            "reminder_day_start:datetime:YES",
            "manual_funding_usdt:varchar:YES",
            "manual_funding_changed_at:datetime:YES",
        )
        assertThat(
            query(
                "SELECT COLUMN_DEFAULT FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() " +
                    "AND TABLE_NAME = 'account_pnl_monitor_state' AND COLUMN_NAME IN " +
                    "('hedge_mismatch_warned', 'swing_up_sent', 'swing_down_sent')",
            ) { it.getString(1) },
        ).containsExactly("0", "0", "0")
    }

    @Test
    fun `V19 의 기존 상태 행은 보존되고 새 열은 비어 있다`() {
        val v19 = flyway(target = MigrationVersion.fromVersion("19"))
        v19.clean()
        v19.migrate()
        connection().use { c ->
            c.createStatement().use {
                it.executeUpdate(
                    "INSERT INTO account_pnl_monitor_state (id, in_episode, position_fingerprint, outage_notified, " +
                        "version, updated_at) VALUES (1, 1, '0.5|100000000|-0.5|70000', 0, 7, '2026-10-23 09:00:00.000000')",
                )
            }
        }

        assertThat(flyway().migrate().success).isTrue()

        val row = query(
            "SELECT in_episode, position_fingerprint, outage_notified, version, korea_quantity, " +
                "korea_average_buy_price, foreign_signed_quantity, foreign_entry_price, hedge_mismatch_warned, " +
                "swing_bucket_start, swing_baseline_percent, swing_up_sent, swing_down_sent, reminder_day_start, " +
                "manual_funding_usdt, manual_funding_changed_at FROM account_pnl_monitor_state",
        ) { r -> (1..16).joinToString("|") { i -> "${r.getString(i)}" } }
        assertThat(row).containsExactly("1|0.5|100000000|-0.5|70000|0|7|null|null|null|null|0|null|null|0|0|null|null|null")
    }

    @Test
    fun `구 binary 의 상태 INSERT 와 UPDATE 는 V20 에서 동작하고 새 열을 보존한다`() {
        migrateLatest()
        val at = Timestamp.valueOf("2026-10-23 09:00:00")

        connection().use { c ->
            c.prepareStatement(OLD_INSERT_STATE.trimIndent()).use { ps ->
                ps.setBoolean(1, true)
                ps.setString(2, "0.5|100000000|-0.5|70000")
                ps.setTimestamp(3, at)
                ps.setTimestamp(4, at)
                ps.setTimestamp(5, at)
                ps.setBoolean(6, false)
                ps.setTimestamp(7, at)
                assertThat(ps.executeUpdate()).isEqualTo(1)
            }
            c.createStatement().use {
                it.executeUpdate(
                    "UPDATE account_pnl_monitor_state SET korea_quantity = '0.5', korea_average_buy_price = '100000000', " +
                        "foreign_signed_quantity = '-0.5', foreign_entry_price = '70000', " +
                        "swing_bucket_start = '2026-10-23 09:00:00.000000', hedge_mismatch_warned = 1 WHERE id = 1",
                )
            }
            c.prepareStatement(OLD_UPDATE_STATE.trimIndent()).use { ps ->
                ps.setBoolean(1, false)
                ps.setString(2, null)
                ps.setTimestamp(3, at)
                ps.setTimestamp(4, at)
                ps.setTimestamp(5, at)
                ps.setBoolean(6, true)
                ps.setTimestamp(7, at)
                ps.setLong(8, 1)
                assertThat(ps.executeUpdate()).isEqualTo(1)
            }
        }

        val row = query(
            "SELECT version, in_episode, outage_notified, korea_quantity, korea_average_buy_price, " +
                "foreign_signed_quantity, foreign_entry_price, swing_bucket_start, hedge_mismatch_warned " +
                "FROM account_pnl_monitor_state",
        ) { r -> (1..9).joinToString("|") { i -> "${r.getString(i)}" } }
        assertThat(row).containsExactly("2|0|1|0.5|100000000|-0.5|70000|2026-10-23 09:00:00|1")
    }

    @Test
    fun `CHECK 는 불완전한 leg 와 버킷 없는 플래그와 시각 없는 펀딩을 거부한다`() {
        migrateLatest()
        insertState()

        assertThatThrownBy { updateNew("korea_quantity = '0.5'") }.isCheckViolation("ck_account_pnl_monitor_state_legs")
        assertThatThrownBy { updateNew("swing_up_sent = 1") }.isCheckViolation("ck_account_pnl_monitor_state_swing")
        assertThatThrownBy { updateNew("manual_funding_usdt = '3.2'") }
            .isCheckViolation("ck_account_pnl_monitor_state_funding")
    }

    private fun org.assertj.core.api.AbstractThrowableAssert<*, out Throwable>.isCheckViolation(name: String) {
        isInstanceOf(SQLException::class.java)
        satisfies({ e ->
            val sql = e as SQLException
            assertThat(sql.errorCode).isEqualTo(3819)
            assertThat(sql.message).contains(name)
        })
    }

    private fun insertState() {
        connection().use { c ->
            c.createStatement().use {
                it.executeUpdate(
                    "INSERT INTO account_pnl_monitor_state (id, in_episode, outage_notified, version, updated_at) " +
                        "VALUES (1, 0, 0, 1, '2026-10-23 09:00:00.000000')",
                )
            }
        }
    }

    private fun updateNew(assignment: String) {
        connection().use { c -> c.createStatement().use { it.executeUpdate("UPDATE account_pnl_monitor_state SET $assignment WHERE id = 1") } }
    }

    private fun migrateLatest() {
        val flyway = flyway()
        flyway.clean()
        assertThat(flyway.migrate().success).isTrue()
    }

    private fun columns(): List<String> =
        query(
            "SELECT CONCAT(COLUMN_NAME, ':', DATA_TYPE, ':', IS_NULLABLE) FROM information_schema.COLUMNS " +
                "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'account_pnl_monitor_state' ORDER BY ORDINAL_POSITION",
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
        /** base(033d513) insertState — 9열. */
        const val OLD_INSERT_STATE = """
INSERT INTO account_pnl_monitor_state (
    id, in_episode, position_fingerprint, last_sent_at,
    last_success_at, monitoring_since, outage_notified, version, updated_at
) VALUES (1, ?, ?, ?, ?, ?, ?, 1, ?)
"""

        /** base(033d513) updateState — 새 열 미포함. */
        const val OLD_UPDATE_STATE = """
UPDATE account_pnl_monitor_state
SET in_episode = ?,
    position_fingerprint = ?,
    last_sent_at = ?,
    last_success_at = ?,
    monitoring_since = ?,
    outage_notified = ?,
    version = version + 1,
    updated_at = ?
WHERE id = 1
  AND version = ?
"""

        @Container
        @JvmStatic
        val mysql: MySQLContainer<*> = MySQLContainer("mysql:8.0")
            .withDatabaseName("premiumspread_owner_account_alert_migration")
            .withUsername("test")
            .withPassword("test")
    }
}
