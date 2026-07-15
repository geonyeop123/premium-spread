package io.premiumspread.infrastructure.common.migration

import org.assertj.core.api.Assertions.assertThat
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
class DurableNotificationMigrationIntegrationTest {

    @Test
    fun `V13 subscription은 default pair와 revision으로 backfill되고 durable queue가 생성된다`() {
        val v13 = flyway(MigrationVersion.fromVersion("13"))
        v13.clean()
        v13.migrate()
        connection().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    INSERT INTO member (email, password, nickname, status, created_at, updated_at)
                    VALUES ('member@example.com', 'encoded', 'member', 'ACTIVE', NOW(6), NOW(6))
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    INSERT INTO notification_subscription (
                        member_id, symbol, direction, threshold, status, created_at, updated_at
                    ) VALUES (1, 'BTC', 'ABOVE', 5.0, 'ACTIVE', NOW(6), NOW(6))
                    """.trimIndent(),
                )
            }
        }

        assertThat(flyway().migrate().success).isTrue()

        connection().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT korea_exchange, foreign_exchange, revision FROM notification_subscription",
                ).use { result ->
                    assertThat(result.next()).isTrue()
                    assertThat(result.getString("korea_exchange")).isEqualTo("BITHUMB")
                    assertThat(result.getString("foreign_exchange")).isEqualTo("BINANCE")
                    assertThat(result.getLong("revision")).isEqualTo(1)
                }
            }
            assertThat(
                connection.metaData.getTables(mysql.databaseName, null, "notification_delivery", null).use { it.next() },
            ).isTrue()
            val subscriptionColumns = connection.metaData.getColumns(
                mysql.databaseName,
                null,
                "notification_subscription",
                null,
            ).use { result ->
                buildMap {
                    while (result.next()) put(result.getString("COLUMN_NAME"), result.getInt("NULLABLE"))
                }
            }
            assertThat(subscriptionColumns).containsKeys(
                "korea_exchange",
                "foreign_exchange",
                "revision",
                "lock_version",
            )
            assertThat(subscriptionColumns.filterKeys { it in setOf("korea_exchange", "foreign_exchange", "revision") })
                .allSatisfy { _, nullable -> assertThat(nullable).isEqualTo(0) }
            val deliveryColumns = connection.metaData.getColumns(
                mysql.databaseName,
                null,
                "notification_delivery",
                null,
            ).use { result ->
                buildSet {
                    while (result.next()) add(result.getString("COLUMN_NAME"))
                }
            }
            assertThat(deliveryColumns).contains(
                "id",
                "delivery_id",
                "subscription_id",
                "event_key",
                "recipient_email",
                "subject",
                "payload",
                "status",
                "attempt_count",
                "next_attempt_at",
                "locked_at",
                "locked_by",
                "claim_token",
                "sent_at",
                "last_error",
                "scrubbed_at",
                "redrive_actor",
                "redrive_reason",
                "redriven_at",
                "created_at",
                "updated_at",
            )
            val activeIndex = connection.metaData.getIndexInfo(
                mysql.databaseName,
                null,
                "notification_subscription",
                false,
                false,
            ).use { result ->
                buildList {
                    while (result.next()) {
                        if (result.getString("INDEX_NAME") == "idx_notification_subscription_active_pair_direction") {
                            add(result.getString("COLUMN_NAME"))
                        }
                    }
                }
            }
            assertThat(activeIndex).containsExactly(
                "status",
                "symbol",
                "korea_exchange",
                "foreign_exchange",
                "direction",
            )
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

    private fun connection() = DriverManager.getConnection(jdbcUrl, mysql.username, mysql.password)

    private val jdbcUrl: String
        get() = mysql.jdbcUrl + if (mysql.jdbcUrl.contains('?')) "&sslMode=DISABLED" else "?sslMode=DISABLED"

    companion object {
        @Container
        @JvmStatic
        val mysql: MySQLContainer<*> = MySQLContainer("mysql:8.0")
            .withDatabaseName("premiumspread_notification_migration")
            .withUsername("test")
            .withPassword("test")
    }
}
