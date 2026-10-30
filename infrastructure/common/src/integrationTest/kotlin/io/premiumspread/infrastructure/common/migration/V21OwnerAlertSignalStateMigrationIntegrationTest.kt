package io.premiumspread.infrastructure.common.migration

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import io.premiumspread.infrastructure.common.support.SharedMySqlContainer
import io.premiumspread.infrastructure.common.support.TestDatabase
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.SQLException

/** `V21` — owner 알림 신호별 지속 상태(#91) 테이블과 CHECK 2개. */
@Tag("integration")
class V21OwnerAlertSignalStateMigrationIntegrationTest {

    @Test
    fun `빈 DB 에서 latest 는 V21 까지 성공하고 열이 설계와 같다`() {
        val flyway = flyway()
        flyway.clean()

        assertThat(flyway.migrate().success).isTrue()

        assertThat(appliedVersions()).containsAll((1..21).map(Int::toString))
        assertThat(columns()).containsExactly(
            "signal_key:varchar:NO",
            "alert_kind:varchar:NO",
            "active:tinyint:NO",
            "incident_started_at:datetime:YES",
            "last_notified_at:datetime:YES",
            "notify_count:int:NO",
            "version:bigint:NO",
            "updated_at:datetime:NO",
        )
        assertThat(
            query(
                "SELECT COLUMN_NAME FROM information_schema.KEY_COLUMN_USAGE WHERE TABLE_SCHEMA = DATABASE() " +
                    "AND TABLE_NAME = 'owner_alert_signal_state' AND CONSTRAINT_NAME = 'PRIMARY' ORDER BY ORDINAL_POSITION",
            ) { it.getString(1) },
        ).containsExactly("signal_key")
        assertThat(
            query(
                "SELECT TABLE_COLLATION FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() " +
                    "AND TABLE_NAME = 'owner_alert_signal_state'",
            ) { it.getString(1) },
        ).containsExactly("utf8mb4_unicode_ci")
    }

    @Test
    fun `V20 에서 V21 로 올려도 기존 행이 그대로다`() {
        val v20 = flyway(target = MigrationVersion.fromVersion("20"))
        v20.clean()
        v20.migrate()
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
            }
        }
        val stateBefore = stateRows()
        val mailBefore = mailRows()
        assertThat(stateBefore).hasSize(1)
        assertThat(mailBefore).hasSize(1)

        assertThat(flyway().migrate().success).isTrue()

        assertThat(stateRows()).isEqualTo(stateBefore)
        assertThat(mailRows()).isEqualTo(mailBefore)
        assertThat(query("SELECT COUNT(*) FROM owner_alert_signal_state") { it.getLong(1) }).containsExactly(0L)
    }

    @Test
    fun `CHECK 가 어긋난 행을 거부한다`() {
        val flyway = flyway()
        flyway.clean()
        assertThat(flyway.migrate().success).isTrue()

        // active = TRUE 인데 incident_started_at 이 NULL
        assertThatThrownBy { insert("a", true, null, "2026-10-28 09:00:00.000000", 1, 1) }
            .isCheckViolation("ck_owner_alert_signal_state_incident")
        // active = FALSE 인데 notify_count = 1
        assertThatThrownBy { insert("b", false, null, null, 1, 1) }
            .isCheckViolation("ck_owner_alert_signal_state_incident")
        // last_notified_at < incident_started_at
        assertThatThrownBy { insert("c", true, "2026-10-28 09:00:00.000000", "2026-10-28 08:00:00.000000", 1, 1) }
            .isCheckViolation("ck_owner_alert_signal_state_incident")
        // version = 0
        assertThatThrownBy { insert("d", false, null, null, 0, 0) }
            .isCheckViolation("ck_owner_alert_signal_state_version")
    }

    private fun insert(key: String, active: Boolean, startedAt: String?, notifiedAt: String?, count: Int, version: Long) {
        connection().use { c ->
            c.prepareStatement(
                "INSERT INTO owner_alert_signal_state (signal_key, alert_kind, active, incident_started_at, " +
                    "last_notified_at, notify_count, version, updated_at) " +
                    "VALUES (?, 'OPS', ?, ?, ?, ?, ?, '2026-10-28 09:00:00.000000')",
            ).use { ps ->
                ps.setString(1, key)
                ps.setBoolean(2, active)
                ps.setString(3, startedAt)
                ps.setString(4, notifiedAt)
                ps.setInt(5, count)
                ps.setLong(6, version)
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

    private fun columns(): List<String> =
        query(
            "SELECT CONCAT(COLUMN_NAME, ':', DATA_TYPE, ':', IS_NULLABLE) FROM information_schema.COLUMNS " +
                "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'owner_alert_signal_state' ORDER BY ORDINAL_POSITION",
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
        val mysql: TestDatabase = SharedMySqlContainer.database("premiumspread_owner_alert_signal_migration")
    }
}
