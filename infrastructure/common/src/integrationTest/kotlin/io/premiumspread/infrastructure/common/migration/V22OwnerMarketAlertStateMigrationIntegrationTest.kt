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

/** `V22` — owner 시장 알림(#90) 신호별 상태·실계정 최신 순수익률 테이블과 CHECK. */
@Tag("integration")
@Testcontainers
class V22OwnerMarketAlertStateMigrationIntegrationTest {

    @Test
    fun `빈 DB 에서 latest 는 V22 까지 성공하고 열이 설계와 같다`() {
        val flyway = flyway()
        flyway.clean()

        assertThat(flyway.migrate().success).isTrue()

        assertThat(appliedVersions()).containsAll((1..22).map(Int::toString))
        assertThat(columns("owner_market_alert_state")).containsExactly(
            "signal_key:varchar:NO",
            "alert_kind:varchar:NO",
            "last_notified_at:datetime:YES",
            "premium_zone:varchar:YES",
            "upper_percent:decimal:YES",
            "lower_percent:decimal:YES",
            "hysteresis_percent:decimal:YES",
            "summary_date:date:YES",
            "version:bigint:NO",
            "updated_at:datetime:NO",
        )
        assertThat(columns("account_pnl_latest")).containsExactly(
            "id:tinyint:NO",
            "has_position:tinyint:NO",
            "net_percent:decimal:YES",
            "net_krw:decimal:YES",
            "calculated_at:datetime:NO",
            "updated_at:datetime:NO",
        )
        assertThat(primaryKey("owner_market_alert_state")).containsExactly("signal_key")
        assertThat(primaryKey("account_pnl_latest")).containsExactly("id")
        listOf("owner_market_alert_state", "account_pnl_latest").forEach { table ->
            assertThat(
                query(
                    "SELECT TABLE_COLLATION FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() " +
                        "AND TABLE_NAME = '$table'",
                ) { it.getString(1) },
            ).containsExactly("utf8mb4_unicode_ci")
        }
    }

    @Test
    fun `V21 에서 V22 로 올려도 기존 행이 그대로다`() {
        val v21 = flyway(target = MigrationVersion.fromVersion("21"))
        v21.clean()
        v21.migrate()
        assertThat(newTables()).isEmpty()
        connection().use { c ->
            c.createStatement().use {
                it.executeUpdate(
                    "INSERT INTO account_pnl_monitor_state (id, in_episode, position_fingerprint, outage_notified, " +
                        "version, updated_at) VALUES (1, 1, '0.5|100000000|-0.5|70000', 0, 7, '2026-10-23 09:00:00.000000')",
                )
                it.executeUpdate(
                    "INSERT INTO account_pnl_mail (event_key, subject, body, status, attempt_count, created_at, channel) " +
                        "VALUES ('evt-1', 'subject', 'body', 'PENDING', 2, '2026-10-23 09:00:00.000000', 'EMAIL')",
                )
                it.executeUpdate(
                    "INSERT INTO owner_alert_signal_state (signal_key, alert_kind, active, incident_started_at, " +
                        "last_notified_at, notify_count, version, updated_at) VALUES ('ops.x', 'OPS', 1, " +
                        "'2026-10-28 08:00:00.000000', '2026-10-28 09:00:00.000000', 2, 3, '2026-10-28 09:00:00.000000')",
                )
            }
        }
        val stateBefore = stateRows()
        val mailBefore = mailRows()
        val signalBefore = signalRows()
        assertThat(stateBefore).hasSize(1)
        assertThat(mailBefore).hasSize(1)
        assertThat(signalBefore).hasSize(1)

        assertThat(flyway().migrate().success).isTrue()

        assertThat(stateRows()).isEqualTo(stateBefore)
        assertThat(mailRows()).isEqualTo(mailBefore)
        assertThat(signalRows()).isEqualTo(signalBefore)
        assertThat(newTables()).containsExactlyInAnyOrder("owner_market_alert_state", "account_pnl_latest")
        assertThat(query("SELECT COUNT(*) FROM owner_market_alert_state") { it.getLong(1) }).containsExactly(0L)
        assertThat(query("SELECT COUNT(*) FROM account_pnl_latest") { it.getLong(1) }).containsExactly(0L)
    }

    @Test
    fun `CHECK 가 어긋난 행을 거부한다`() {
        val flyway = flyway()
        flyway.clean()
        assertThat(flyway.migrate().success).isTrue()

        // zone 만 있고 hysteresis NULL
        assertThatThrownBy { insertState("a", "ABOVE", "3.0000", null, null, 1) }
            .isCheckViolation("ck_owner_market_alert_state_zone")
        // premium_zone 이 허용 밖 값
        assertThatThrownBy { insertState("b", "UP", "3.0000", null, "0.1000", 1) }
            .isCheckViolation("ck_owner_market_alert_state_zone")
        // zone 은 있는데 upper·lower 모두 NULL
        assertThatThrownBy { insertState("c", "BETWEEN", null, null, "0.1000", 1) }
            .isCheckViolation("ck_owner_market_alert_state_zone")
        // hysteresis 음수
        assertThatThrownBy { insertState("d", "BELOW", "3.0000", null, "-0.1000", 1) }
            .isCheckViolation("ck_owner_market_alert_state_zone")
        // version 0
        assertThatThrownBy { insertState("e", null, null, null, null, 0) }
            .isCheckViolation("ck_owner_market_alert_state_version")
        // account_pnl_latest.id = 2
        assertThatThrownBy { insertLatest(2, false, null, null) }
            .isCheckViolation("ck_account_pnl_latest_singleton")
        // has_position = TRUE 인데 net_krw NULL
        assertThatThrownBy { insertLatest(1, true, "1.23", null) }
            .isCheckViolation("ck_account_pnl_latest_values")
        // has_position = FALSE 인데 net_percent 값
        assertThatThrownBy { insertLatest(1, false, "1.23", null) }
            .isCheckViolation("ck_account_pnl_latest_values")
    }

    private fun insertState(
        key: String,
        zone: String?,
        upper: String?,
        lower: String?,
        hysteresis: String?,
        version: Long,
    ) {
        connection().use { c ->
            c.prepareStatement(
                "INSERT INTO owner_market_alert_state (signal_key, alert_kind, premium_zone, upper_percent, " +
                    "lower_percent, hysteresis_percent, version, updated_at) " +
                    "VALUES (?, 'THRESHOLD', ?, ?, ?, ?, ?, '2026-10-28 09:00:00.000000')",
            ).use { ps ->
                ps.setString(1, key)
                ps.setString(2, zone)
                ps.setString(3, upper)
                ps.setString(4, lower)
                ps.setString(5, hysteresis)
                ps.setLong(6, version)
                ps.executeUpdate()
            }
        }
    }

    private fun insertLatest(id: Int, hasPosition: Boolean, percent: String?, krw: String?) {
        connection().use { c ->
            c.prepareStatement(
                "INSERT INTO account_pnl_latest (id, has_position, net_percent, net_krw, calculated_at, updated_at) " +
                    "VALUES (?, ?, ?, ?, '2026-10-28 09:00:00.000000', '2026-10-28 09:00:00.000000')",
            ).use { ps ->
                ps.setInt(1, id)
                ps.setBoolean(2, hasPosition)
                ps.setString(3, percent)
                ps.setString(4, krw)
                ps.executeUpdate()
            }
        }
    }

    private fun org.assertj.core.api.AbstractThrowableAssert<*, out Throwable>.isCheckViolation(name: String) {
        isInstanceOf(SQLException::class.java)
        satisfies({ e ->
            val sql = e as SQLException
            assertThat(sql.errorCode).isEqualTo(3819)
            assertThat(sql.message).contains(name)
        })
    }

    private fun stateRows(): List<String> =
        query(
            "SELECT id, in_episode, position_fingerprint, outage_notified, version, updated_at FROM account_pnl_monitor_state",
        ) { r -> (1..6).joinToString("|") { i -> "${r.getString(i)}" } }

    private fun mailRows(): List<String> =
        query(
            "SELECT event_key, subject, body, status, attempt_count, channel, created_at FROM account_pnl_mail",
        ) { r -> (1..7).joinToString("|") { i -> "${r.getString(i)}" } }

    private fun signalRows(): List<String> =
        query(
            "SELECT signal_key, alert_kind, active, incident_started_at, last_notified_at, notify_count, version, " +
                "updated_at FROM owner_alert_signal_state",
        ) { r -> (1..8).joinToString("|") { i -> "${r.getString(i)}" } }

    private fun newTables(): List<String> =
        query(
            "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() " +
                "AND TABLE_NAME IN ('owner_market_alert_state', 'account_pnl_latest')",
        ) { it.getString(1) }

    private fun columns(table: String): List<String> =
        query(
            "SELECT CONCAT(COLUMN_NAME, ':', DATA_TYPE, ':', IS_NULLABLE) FROM information_schema.COLUMNS " +
                "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = '$table' ORDER BY ORDINAL_POSITION",
        ) { it.getString(1) }

    private fun primaryKey(table: String): List<String> =
        query(
            "SELECT COLUMN_NAME FROM information_schema.KEY_COLUMN_USAGE WHERE TABLE_SCHEMA = DATABASE() " +
                "AND TABLE_NAME = '$table' AND CONSTRAINT_NAME = 'PRIMARY' ORDER BY ORDINAL_POSITION",
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
            .withDatabaseName("premiumspread_owner_market_alert_migration")
            .withUsername("test")
            .withPassword("test")
    }
}
