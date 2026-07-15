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
import java.sql.SQLException

@Tag("integration")
@Testcontainers
class PremiumPairMigrationIntegrationTest {

    @Test
    fun `빈 DB는 V14까지 순서대로 migration 된다`() {
        val flyway = flyway()
        flyway.clean()

        val result = flyway.migrate()

        assertThat(result.success).isTrue()
        assertThat(result.migrationsExecuted).isGreaterThanOrEqualTo(14)
        assertThat(appliedVersions()).containsAll((1..14).map(Int::toString))
        assertPairColumnsAndIndexes()
    }

    @Test
    fun `V12 현재 schema의 premium 데이터는 default pair로 backfill하고 pair 단위로 유일하다`() {
        val currentFlyway = flyway(MigrationVersion.fromVersion("12"))
        currentFlyway.clean()
        currentFlyway.migrate()
        insertCurrentSchemaRows()

        flyway().migrate()

        connection().use { connection ->
            PREMIUM_TABLES.forEach { table ->
                connection.createStatement().use { statement ->
                    statement.executeQuery(
                        "SELECT korea_exchange, foreign_exchange FROM $table WHERE symbol = 'BTC'",
                    ).use { result ->
                        assertThat(result.next()).describedAs("$table backfill row").isTrue()
                        assertThat(result.getString("korea_exchange")).isEqualTo("BITHUMB")
                        assertThat(result.getString("foreign_exchange")).isEqualTo("BINANCE")
                    }
                }
            }

            insertPremiumMinute(connection, "UPBIT", "BINANCE")
            assertThatThrownBy { insertPremiumMinute(connection, "BITHUMB", "BINANCE") }
                .isInstanceOf(SQLException::class.java)
        }
        assertPairColumnsAndIndexes()
    }

    private fun flyway(target: MigrationVersion? = null): Flyway {
        val configuration = Flyway.configure()
            .dataSource(jdbcUrl, mysql.username, mysql.password)
            .locations("classpath:db/migration")
            .cleanDisabled(false)
        if (target != null) configuration.target(target)
        return configuration.load()
    }

    private fun insertCurrentSchemaRows() {
        connection().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    INSERT INTO premium_snapshot (
                        symbol, premium_rate, korea_price, foreign_price, foreign_price_krw,
                        fx_rate, observed_at, created_at
                    ) VALUES ('BTC', 3.1000, 100000000, 70000, 98000000, 1400, NOW(6), NOW(6))
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    INSERT INTO premium_minute (symbol, minute_at, high, low, open, close, avg, count)
                    VALUES ('BTC', '2026-07-14 10:00:00', 3, 1, 1, 2, 2, 60)
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    INSERT INTO premium_hour (symbol, hour_at, high, low, open, close, avg, count)
                    VALUES ('BTC', '2026-07-14 10:00:00', 3, 1, 1, 2, 2, 60)
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    INSERT INTO premium_day (symbol, day_at, high, low, open, close, avg, count)
                    VALUES ('BTC', '2026-07-14', 3, 1, 1, 2, 2, 60)
                    """.trimIndent(),
                )
            }
        }
    }

    private fun insertPremiumMinute(connection: Connection, korea: String, foreign: String) {
        connection.prepareStatement(
            """
            INSERT INTO premium_minute (
                symbol, korea_exchange, foreign_exchange, minute_at,
                high, low, open, close, avg, count
            ) VALUES ('BTC', ?, ?, '2026-07-14 10:00:00', 4, 2, 2, 3, 3, 60)
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, korea)
            statement.setString(2, foreign)
            statement.executeUpdate()
        }
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

    private fun assertPairColumnsAndIndexes() {
        connection().use { connection ->
            PREMIUM_TABLES.forEach { table ->
                val columns = connection.metaData.getColumns(mysql.databaseName, null, table, null).use { result ->
                    buildMap {
                        while (result.next()) put(result.getString("COLUMN_NAME"), result.getInt("NULLABLE"))
                    }
                }
                assertThat(columns).containsKeys("korea_exchange", "foreign_exchange")
                assertThat(columns.getValue("korea_exchange")).isEqualTo(0)
                assertThat(columns.getValue("foreign_exchange")).isEqualTo(0)
            }

            assertThat(indexColumns(connection, "premium_snapshot", uniqueOnly = false))
                .containsEntry(
                    "idx_snapshot_pair_symbol_observed",
                    listOf("korea_exchange", "foreign_exchange", "symbol", "observed_at"),
                )
            assertThat(indexColumns(connection, "premium_minute", uniqueOnly = true))
                .containsEntry(
                    "uk_pair_symbol_minute",
                    listOf("korea_exchange", "foreign_exchange", "symbol", "minute_at"),
                )
            assertThat(indexColumns(connection, "premium_hour", uniqueOnly = true))
                .containsEntry(
                    "uk_pair_symbol_hour",
                    listOf("korea_exchange", "foreign_exchange", "symbol", "hour_at"),
                )
            assertThat(indexColumns(connection, "premium_day", uniqueOnly = true))
                .containsEntry(
                    "uk_pair_symbol_day",
                    listOf("korea_exchange", "foreign_exchange", "symbol", "day_at"),
                )
        }
    }

    private fun indexColumns(
        connection: Connection,
        table: String,
        uniqueOnly: Boolean,
    ): Map<String, List<String>> =
        connection.metaData.getIndexInfo(mysql.databaseName, null, table, uniqueOnly, false).use { result ->
            buildMap<String, MutableList<Pair<Int, String>>> {
                while (result.next()) {
                    val indexName = result.getString("INDEX_NAME") ?: continue
                    val columnName = result.getString("COLUMN_NAME") ?: continue
                    getOrPut(indexName) { mutableListOf() }
                        .add(result.getInt("ORDINAL_POSITION") to columnName)
                }
            }.mapValues { (_, columns) -> columns.sortedBy { it.first }.map { it.second } }
        }

    private fun connection() = DriverManager.getConnection(jdbcUrl, mysql.username, mysql.password)

    private val jdbcUrl: String
        get() = mysql.jdbcUrl + if (mysql.jdbcUrl.contains('?')) "&sslMode=DISABLED" else "?sslMode=DISABLED"

    companion object {
        private val PREMIUM_TABLES = listOf("premium_snapshot", "premium_minute", "premium_hour", "premium_day")

        @Container
        @JvmStatic
        val mysql: MySQLContainer<*> = MySQLContainer("mysql:8.0")
            .withDatabaseName("premiumspread_migration")
            .withUsername("test")
            .withPassword("test")
    }
}
