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

/** `V28` — 레그 관측 이력(#139) 두 테이블. 기존 V24 행은 보존되고 제약 이름이 위반 메시지에 드러나야 한다. */
@Tag("integration")
@Testcontainers
class V28LegObservationMigrationIntegrationTest {

    @Test
    fun `V27 상태의 기존 행에 V28 을 적용하면 기존 행은 그대로이고 두 테이블이 생긴다`() {
        val v27 = flyway(target = MigrationVersion.fromVersion("27"))
        v27.clean()
        v27.migrate()
        assertThat(tables()).doesNotContain(FOREIGN, KOREA)
        insertEpisode()
        insertObservation("2026-10-23 09:01:00.000000")
        exec(
            "INSERT INTO account_position_event (episode_id, seq, event_type, occurred_at, korea_quantity, " +
                "korea_average_price, foreign_signed_quantity, foreign_entry_price, hedged, position_source, " +
                "created_at) VALUES (1, 1, 'OPENED', '2026-10-23 09:00:00.000000', '0.2', '100', '-0.2', '70', " +
                "1, 'MANUAL_ENV', '2026-10-23 09:00:00.000000')",
        )
        val before = snapshot()

        assertThat(flyway().migrate().success).isTrue()

        assertThat(appliedVersions()).contains("28")
        assertThat(tables()).contains(FOREIGN, KOREA)
        assertThat(snapshot()).isEqualTo(before)
    }

    @Test
    fun `정상 행은 전부 NULL 지표도 전부 값도 삽입된다`() {
        migrateLatest()
        insertEpisode()
        insertObservation("2026-10-23 09:01:00.000000")
        insertObservation("2026-10-23 09:02:00.000000")

        exec(
            "INSERT INTO account_foreign_leg_observation (episode_id, observed_at, position_source, created_at) " +
                "VALUES (1, '2026-10-23 09:01:00.000000', 'EXCHANGE_API', '2026-10-23 09:01:00.000000')",
        )
        exec(
            "INSERT INTO account_foreign_leg_observation VALUES (1, '2026-10-23 09:02:00.000000', 5, 'ISOLATED', " +
                "'60.5', '70.1', '10', '1', '0.1', '0.2', 'EXCHANGE_API', '2026-10-23 09:02:00.000000')",
        )
        exec(
            "INSERT INTO account_korea_leg_observation VALUES (1, '2026-10-23 09:01:00.000000', NULL, " +
                "'2026-10-23 09:01:00.000000')",
        )
        exec(
            "INSERT INTO account_korea_leg_observation VALUES (1, '2026-10-23 09:02:00.000000', '12.5', " +
                "'2026-10-23 09:02:00.000000')",
        )

        assertThat(query("SELECT COUNT(*) FROM account_foreign_leg_observation") { it.getInt(1) }).containsExactly(2)
        assertThat(query("SELECT COUNT(*) FROM account_korea_leg_observation") { it.getInt(1) }).containsExactly(2)
    }

    @Test
    fun `관측 없는 키는 FK 이름과 함께 거부된다`() {
        migrateLatest()
        insertEpisode()

        assertThatThrownBy {
            exec(
                "INSERT INTO account_foreign_leg_observation (episode_id, observed_at, position_source, created_at) " +
                    "VALUES (1, '2026-10-23 09:01:00.000000', 'EXCHANGE_API', '2026-10-23 09:01:00.000000')",
            )
        }.isInstanceOf(SQLException::class.java)
            .satisfies({ e -> assertThat(e.message).contains("fk_account_foreign_leg_observation_round") })
        assertThatThrownBy {
            exec(
                "INSERT INTO account_korea_leg_observation (episode_id, observed_at, created_at) " +
                    "VALUES (1, '2026-10-23 09:01:00.000000', '2026-10-23 09:01:00.000000')",
            )
        }.isInstanceOf(SQLException::class.java)
            .satisfies({ e -> assertThat(e.message).contains("fk_account_korea_leg_observation_round") })
    }

    @Test
    fun `바이낸스 CHECK 위반은 제약 이름과 함께 거부된다`() {
        migrateLatest()
        insertEpisode()
        insertObservation("2026-10-23 09:01:00.000000")
        val prefix = "INSERT INTO account_foreign_leg_observation " +
            "(episode_id, observed_at, leverage, margin_type, mark_price, isolated_margin, maint_margin, " +
            "position_source, created_at) VALUES (1, '2026-10-23 09:01:00.000000', "
        val suffix = "'2026-10-23 09:01:00.000000')"

        assertThatThrownBy { exec(prefix + "5, 'BAD', NULL, NULL, NULL, 'EXCHANGE_API', $suffix") }
            .isInstanceOf(SQLException::class.java)
            .satisfies({ e -> assertThat(e.message).contains("ck_account_foreign_leg_observation_config") })
        assertThatThrownBy { exec(prefix + "5, NULL, NULL, NULL, NULL, 'EXCHANGE_API', $suffix") }
            .isInstanceOf(SQLException::class.java)
            .satisfies({ e -> assertThat(e.message).contains("ck_account_foreign_leg_observation_config") })
        assertThatThrownBy { exec(prefix + "NULL, NULL, '70', NULL, NULL, 'EXCHANGE_API', $suffix") }
            .isInstanceOf(SQLException::class.java)
            .satisfies({ e -> assertThat(e.message).contains("ck_account_foreign_leg_observation_risk") })
        assertThatThrownBy { exec(prefix + "NULL, NULL, NULL, NULL, NULL, 'MANUAL_ENV', $suffix") }
            .isInstanceOf(SQLException::class.java)
            .satisfies({ e -> assertThat(e.message).contains("ck_account_foreign_leg_observation_source") })
    }

    private fun migrateLatest() {
        val flyway = flyway()
        flyway.clean()
        assertThat(flyway.migrate().success).isTrue()
    }

    private fun insertEpisode() {
        exec(
            "INSERT INTO account_position_episode (status, opened_at, closed_at, korea_quantity, " +
                "korea_average_price, foreign_signed_quantity, foreign_entry_price, position_source, " +
                "event_count, created_at, updated_at) VALUES ('OPEN', '2026-10-23 09:00:00.000000', NULL, " +
                "'0.2', '100', '-0.2', '70', 'MANUAL_ENV', 1, " +
                "'2026-10-23 09:00:00.000000', '2026-10-23 09:00:01.000000')",
        )
    }

    private fun insertObservation(observedAt: String) {
        exec(
            "INSERT INTO account_pnl_observation (episode_id, kind, observed_at, net_percent, net_krw, " +
                "current_premium_percent, entry_premium_percent, premium_gap_percent_point, fx_rate, " +
                "korea_exit_price, foreign_exit_price, created_at) VALUES (1, 'HOLDING', '$observedAt', 1.00, 100, " +
                "1.00, 1.00, 0.00, 1400, 100, 70, '$observedAt')",
        )
    }

    private fun snapshot(): List<String> =
        query(
            "SELECT CONCAT_WS('|', 'e', id, status, opened_at, korea_quantity, event_count) " +
                "FROM account_position_episode UNION ALL " +
                "SELECT CONCAT_WS('|', 'v', id, episode_id, seq, event_type, occurred_at) FROM account_position_event " +
                "UNION ALL SELECT CONCAT_WS('|', 'o', id, episode_id, kind, observed_at, net_percent, net_krw, fx_rate) " +
                "FROM account_pnl_observation",
        ) { it.getString(1) }

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
        private const val FOREIGN = "account_foreign_leg_observation"
        private const val KOREA = "account_korea_leg_observation"

        @Container
        @JvmStatic
        val mysql: MySQLContainer<*> = MySQLContainer("mysql:8.0")
            .withDatabaseName("premiumspread_leg_observation_migration")
            .withUsername("test")
            .withPassword("test")
    }
}
