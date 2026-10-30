package io.premiumspread.infrastructure.common.support

import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Statement
import java.sql.Timestamp
import java.time.Instant

@Tag("integration")
class LatestSchemaFixtureIntegrationTest {

    @BeforeEach
    fun setUp() {
        fixture.reset()
    }

    @Test
    fun `reset 은 history 를 뺀 모든 테이블을 비우고 AUTO_INCREMENT 를 되돌린다`() {
        connect(mysql).use { connection ->
            insertMember(connection, "a@example.com")
            val memberId = insertMember(connection, "b@example.com")
            insertSubscription(connection, memberId)
            val historyBefore = count(connection, "flyway_schema_history")
            connection.createStatement().use { it.execute("DELETE FROM notification_subscription") }

            fixture.reset()

            val tables = connection.createStatement().use { s ->
                s.executeQuery(
                    "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() " +
                        "AND TABLE_TYPE = 'BASE TABLE' AND TABLE_NAME <> 'flyway_schema_history'",
                ).use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
            }
            assertThat(tables).isNotEmpty()
            tables.forEach { assertThat(count(connection, it)).describedAs(it).isZero() }
            assertThat(count(connection, "flyway_schema_history")).isEqualTo(historyBefore)

            val newMemberId = insertMember(connection, "c@example.com")
            insertSubscription(connection, newMemberId)
            val id = connection.createStatement().use { s ->
                s.executeQuery("SELECT id FROM member").use { rs ->
                    rs.next()
                    rs.getLong(1)
                }
            }
            assertThat(id).isEqualTo(1L)
            val subscriptionId = connection.createStatement().use { s ->
                s.executeQuery("SELECT id FROM notification_subscription").use { rs ->
                    rs.next()
                    rs.getLong(1)
                }
            }
            assertThat(subscriptionId).isEqualTo(1L)
        }
    }

    @Test
    fun `reset 은 지워진 CHECK 제약을 복구한다`() {
        connect(mysql).use { connection ->
            connection.createStatement().use {
                it.execute("ALTER TABLE owner_market_alert_state DROP CHECK ck_owner_market_alert_state_zone")
            }
            assertThat(zoneCheckCount(connection)).isZero()

            fixture.reset()

            assertThat(zoneCheckCount(connection)).isEqualTo(1)
        }
    }

    @Test
    fun `첫 reset 은 빈 database 를 최신 schema 로 만든다`() {
        firstFixture.reset()

        val info = Flyway.configure()
            .dataSource(firstMysql.utcJdbcUrl, firstMysql.username, firstMysql.password)
            .locations("classpath:db/migration")
            .load()
            .info()
        assertThat(info.pending()).isEmpty()
        assertThat(info.applied().size).isEqualTo(info.all().size)
    }

    private fun connect(db: TestDatabase): Connection =
        DriverManager.getConnection(db.utcJdbcUrl, db.username, db.password)

    private fun insertSubscription(connection: Connection, memberId: Long) {
        connection.prepareStatement(
            """
            INSERT INTO notification_subscription (
                member_id, symbol, korea_exchange, foreign_exchange, revision,
                direction, threshold, status, created_at, updated_at
            ) VALUES (?, 'BTC', 'BITHUMB', 'BINANCE', 1, 'ABOVE', 5.0, 'ACTIVE', ?, ?)
            """.trimIndent(),
        ).use {
            it.setLong(1, memberId)
            it.setTimestamp(2, Timestamp.from(NOW))
            it.setTimestamp(3, Timestamp.from(NOW))
            it.executeUpdate()
        }
    }

    private fun insertMember(connection: Connection, email: String): Long =
        connection.prepareStatement(
            """
            INSERT INTO member (email, password, nickname, status, created_at, updated_at)
            VALUES (?, 'encoded', 'member', 'ACTIVE', ?, ?)
            """.trimIndent(),
            Statement.RETURN_GENERATED_KEYS,
        ).use {
            it.setString(1, email)
            it.setTimestamp(2, Timestamp.from(NOW))
            it.setTimestamp(3, Timestamp.from(NOW))
            it.executeUpdate()
            it.generatedKeys.use { keys ->
                keys.next()
                keys.getLong(1)
            }
        }

    private fun count(connection: Connection, table: String): Long =
        connection.createStatement().use { s ->
            s.executeQuery("SELECT COUNT(*) FROM `$table`").use { rs ->
                rs.next()
                rs.getLong(1)
            }
        }

    private fun zoneCheckCount(connection: Connection): Int =
        connection.createStatement().use { s ->
            s.executeQuery(
                "SELECT COUNT(*) FROM information_schema.CHECK_CONSTRAINTS WHERE CONSTRAINT_SCHEMA = DATABASE() " +
                    "AND CONSTRAINT_NAME = 'ck_owner_market_alert_state_zone'",
            ).use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }

    companion object {
        private val NOW = Instant.parse("2026-07-15T01:00:00Z")

        val mysql: TestDatabase = SharedMySqlContainer.database("premiumspread_latest_schema_fixture")
        private val fixture = LatestSchemaFixture(mysql)
        val firstMysql: TestDatabase = SharedMySqlContainer.database("premiumspread_latest_schema_first")
        private val firstFixture = LatestSchemaFixture(firstMysql)
    }
}
