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
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLIntegrityConstraintViolationException

@Tag("integration")
@Testcontainers
class V16TradePreparationMigrationIntegrationTest {

    @Test
    fun `빈 DB는 V16까지 순서대로 migration 되고 trade_preparation 컬럼이 갖춰진다`() {
        val flyway = flyway()
        flyway.clean()

        val result = flyway.migrate()

        assertThat(result.success).isTrue()
        assertThat(result.migrationsExecuted).isGreaterThanOrEqualTo(16)
        assertThat(appliedVersions()).containsAll((1..16).map(Int::toString))
        assertTradePreparationSchema()
    }

    @Test
    fun `V15 스키마에 V16을 적용하면 trade_preparation이 생성되고 owner당 활성 계획만 유일하다`() {
        val v15Flyway = flyway(target = MigrationVersion.fromVersion("15"))
        v15Flyway.clean()
        v15Flyway.migrate()

        assertThat(flyway().migrate().success).isTrue()
        assertTradePreparationSchema()

        connection().use { connection ->
            insertMember(connection, id = 1)
            insertMember(connection, id = 2)

            // DRAFT는 active_key가 NULL이라 같은 owner라도 여러 개 허용된다.
            insertTradePreparation(connection, ownerId = 1, status = "DRAFT")
            insertTradePreparation(connection, ownerId = 1, status = "DRAFT")

            // WATCHING·ARMED는 active_key가 owner_id로 채워져 owner당 하나만 허용된다 (D16·D23).
            insertTradePreparation(connection, ownerId = 1, status = "WATCHING")
            assertThatThrownBy { insertTradePreparation(connection, ownerId = 1, status = "ARMED") }
                .isInstanceOf(SQLIntegrityConstraintViolationException::class.java)
                .hasMessageContaining("uk_trade_preparation_owner_active")

            // 다른 owner는 독립적으로 활성 계획을 가질 수 있다.
            insertTradePreparation(connection, ownerId = 2, status = "ARMED")
        }
    }

    private fun assertTradePreparationSchema() {
        connection().use { connection ->
            assertThat(
                connection.metaData.getTables(mysql.databaseName, null, "trade_preparation", null).use { it.next() },
            ).isTrue()

            val columns = connection.metaData.getColumns(mysql.databaseName, null, "trade_preparation", null).use { result ->
                buildMap {
                    while (result.next()) put(result.getString("COLUMN_NAME"), result.getInt("NULLABLE"))
                }
            }
            assertThat(columns.keys).containsExactlyInAnyOrder(
                "id",
                "owner_id",
                "symbol",
                "korea_exchange",
                "foreign_exchange",
                "reference_foreign_price",
                "reference_fx_rate",
                "reference_premium_rate",
                "reference_observed_at",
                "reference_fx_source",
                "reference_fx_observed_at",
                "quantity",
                "leverage",
                "bound_balance_snapshot_id",
                "bound_balance_basis",
                "status",
                "active_key",
                "desired_entry_premium_rate",
                "version",
                "lock_version",
                "invalidation_reason",
                "invalidated_at",
                "condition_first_met_at",
                "condition_first_met_premium_rate",
                "created_at",
                "updated_at",
                "deleted_at",
            )
            val notNullColumns = setOf(
                "owner_id",
                "symbol",
                "korea_exchange",
                "foreign_exchange",
                "reference_foreign_price",
                "reference_fx_rate",
                "reference_premium_rate",
                "reference_observed_at",
                "reference_fx_source",
                "reference_fx_observed_at",
                "quantity",
                "leverage",
                "bound_balance_snapshot_id",
                "bound_balance_basis",
                "status",
                "version",
                "lock_version",
                "created_at",
                "updated_at",
            )
            assertThat(columns.filterKeys { it in notNullColumns }).allSatisfy { _, nullable ->
                assertThat(nullable).isEqualTo(0)
            }

            val activeIndexColumns = connection.metaData.getIndexInfo(
                mysql.databaseName,
                null,
                "trade_preparation",
                true,
                false,
            ).use { result ->
                buildList {
                    while (result.next()) {
                        if (result.getString("INDEX_NAME") == "uk_trade_preparation_owner_active") {
                            add(result.getString("COLUMN_NAME"))
                        }
                    }
                }
            }
            assertThat(activeIndexColumns).containsExactly("active_key")
        }
    }

    private fun insertMember(connection: Connection, id: Long) {
        connection.prepareStatement(
            """
            INSERT INTO member (id, email, password, nickname, status, created_at, updated_at)
            VALUES (?, ?, 'encoded', 'member', 'ACTIVE', NOW(6), NOW(6))
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, id)
            statement.setString(2, "trade-prep-$id@example.com")
            statement.executeUpdate()
        }
    }

    private fun insertTradePreparation(connection: Connection, ownerId: Long, status: String) {
        connection.prepareStatement(
            """
            INSERT INTO trade_preparation (
                owner_id, symbol, korea_exchange, foreign_exchange,
                reference_foreign_price, reference_fx_rate, reference_premium_rate,
                reference_observed_at, reference_fx_source, reference_fx_observed_at,
                quantity, leverage, bound_balance_snapshot_id, bound_balance_basis,
                status, version, lock_version, created_at, updated_at
            ) VALUES (
                ?, 'BTC', 'BITHUMB', 'BINANCE',
                70000, 1400, 3.5,
                NOW(6), 'FX_PROVIDER', NOW(6),
                0.1, 3, 'snapshot-1', 'FRESH',
                ?, 0, 0, NOW(6), NOW(6)
            )
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, ownerId)
            statement.setString(2, status)
            statement.executeUpdate()
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
        connection().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT version FROM flyway_schema_history WHERE success = 1 ORDER BY installed_rank",
                ).use { result ->
                    buildList {
                        while (result.next()) add(result.getString("version"))
                    }
                }
            }
        }

    private fun connection() = DriverManager.getConnection(jdbcUrl, mysql.username, mysql.password)

    private val jdbcUrl: String
        get() = mysql.jdbcUrl + if (mysql.jdbcUrl.contains('?')) "&sslMode=DISABLED" else "?sslMode=DISABLED"

    companion object {
        @Container
        @JvmStatic
        val mysql: MySQLContainer<*> = MySQLContainer("mysql:8.0")
            .withDatabaseName("premiumspread_trade_preparation_migration")
            .withUsername("test")
            .withPassword("test")
    }
}
