package io.premiumspread.config

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

@Tag("integration")
@Testcontainers
class V12MigrationSafetyIntegrationTest {

    @Test
    fun `V11 position 데이터가 있으면 V12 SQL 실행 전에 마이그레이션을 차단한다`() {
        val v11Flyway = flyway(target = MigrationVersion.fromVersion("11"))
        v11Flyway.clean()
        v11Flyway.migrate()
        insertMemberAndPosition()

        val v12Flyway = flyway(
            callback = V12MigrationSafetyCallback(
                V12MigrationSafetyProperties(allowEmptyPositionMigration = true),
            ),
        )

        assertThatThrownBy { v12Flyway.migrate() }
            .hasStackTraceContaining("PENDING_WITH_DATA")

        connection().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM `position`").use { result ->
                    assertThat(result.next()).isTrue()
                    assertThat(result.getLong(1)).isEqualTo(1L)
                }
                statement.executeQuery(
                    "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '12' AND success = 1",
                ).use { result ->
                    assertThat(result.next()).isTrue()
                    assertThat(result.getLong(1)).isZero()
                }
            }
        }
    }

    private fun flyway(
        target: MigrationVersion? = null,
        callback: V12MigrationSafetyCallback? = null,
    ): Flyway {
        val configuration = Flyway.configure()
            .dataSource(jdbcUrl, mysql.username, mysql.password)
            .locations("classpath:db/migration")
            .cleanDisabled(false)
        if (target != null) configuration.target(target)
        if (callback != null) configuration.callbacks(callback)
        return configuration.load()
    }

    private fun insertMemberAndPosition() {
        connection().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    INSERT INTO member (email, password, nickname, status, created_at, updated_at)
                    VALUES ('migration@example.com', 'encoded', 'migration', 'ACTIVE', NOW(6), NOW(6))
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    INSERT INTO position (
                        symbol, exchange, quantity, entry_price, entry_fx_rate,
                        entry_premium_rate, entry_observed_at, status, member_id,
                        created_at, updated_at
                    ) VALUES (
                        'BTC', 'UPBIT', 1.0, 100000000.0, 1400.0,
                        3.5, NOW(6), 'OPEN', 1, NOW(6), NOW(6)
                    )
                    """.trimIndent(),
                )
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
            .withDatabaseName("premiumspread_v12_safety")
            .withUsername("test")
            .withPassword("test")
    }
}
