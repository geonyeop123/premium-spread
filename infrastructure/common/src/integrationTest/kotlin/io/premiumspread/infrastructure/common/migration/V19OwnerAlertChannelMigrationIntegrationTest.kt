package io.premiumspread.infrastructure.common.migration

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import io.premiumspread.infrastructure.common.support.SharedMySqlContainer
import io.premiumspread.infrastructure.common.support.TestDatabase
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.sql.SQLIntegrityConstraintViolationException

/**
 * `V19` — owner 알림 outbox 에 채널 컬럼을 붙이고 UNIQUE 를 `(event_key, channel)` 로 넓힌다.
 *
 * 구 binary(채널을 모르는 INSERT/claim SQL)가 V19 스키마와 롤백 SQL 뒤에도 안전한지 문자열 그대로 재생해 본다.
 */
@Tag("integration")
class V19OwnerAlertChannelMigrationIntegrationTest {

    @Test
    fun `빈 DB는 V19까지 migration 되고 채널 컬럼과 복합 UNIQUE 가 붙는다`() {
        val flyway = flyway()
        flyway.clean()

        assertThat(flyway.migrate().success).isTrue()

        assertThat(appliedVersions()).containsAll((1..19).map(Int::toString))
        assertThat(columns()).containsExactly(
            "id:bigint:NO",
            "event_key:varchar:NO",
            "subject:varchar:NO",
            "body:text:NO",
            "status:varchar:NO",
            "attempt_count:int:NO",
            "locked_by:varchar:YES",
            "claim_token:char:YES",
            "locked_at:datetime:YES",
            "created_at:datetime:NO",
            "sent_at:datetime:YES",
            "channel:varchar:NO",
            "discord_target:varchar:YES",
            "discord_push:varchar:YES",
            "discord_color:int:YES",
        )
        assertThat(
            query(
                "SELECT COLUMN_DEFAULT FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() " +
                    "AND TABLE_NAME = 'account_pnl_mail' AND COLUMN_NAME = 'channel'",
            ) { it.getString(1) },
        ).containsExactly("EMAIL")
        assertThat(indexColumns("uk_account_pnl_mail_event_key_channel")).containsExactly("event_key", "channel")
        assertThat(indexColumns("uk_account_pnl_mail_event_key")).isEmpty()
    }

    @Test
    fun `V18 의 기존 행은 EMAIL 행이 된다`() {
        val v18 = flyway(target = MigrationVersion.fromVersion("18"))
        v18.clean()
        v18.migrate()
        connection().use { c ->
            c.createStatement().use {
                it.executeUpdate(
                    "INSERT INTO account_pnl_mail (event_key, subject, body, status, attempt_count, created_at) " +
                        "VALUES ('k-pending', 's1', 'b1', 'PENDING', 0, '2026-10-23 09:00:00.000000')",
                )
                it.executeUpdate(
                    "INSERT INTO account_pnl_mail (event_key, subject, body, status, attempt_count, created_at, sent_at) " +
                        "VALUES ('k-sent', 's2', 'b2', 'SENT', 2, '2026-10-23 09:00:00.000000', '2026-10-23 09:01:00.000000')",
                )
            }
        }

        assertThat(flyway().migrate().success).isTrue()

        val rows = query(
            "SELECT event_key, channel, discord_target, discord_push, discord_color, subject, body, status, " +
                "attempt_count, sent_at FROM account_pnl_mail ORDER BY id",
        ) {
            listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10).joinToString("|") { i -> "${it.getString(i)}" }
        }
        assertThat(rows).containsExactly(
            "k-pending|EMAIL|null|null|null|s1|b1|PENDING|0|null",
            "k-sent|EMAIL|null|null|null|s2|b2|SENT|2|2026-10-23 09:01:00",
        )
    }

    @Test
    fun `구 binary 의 INSERT 는 V19 에서도 중복 제거된다`() {
        migrateLatest()

        connection().use { c ->
            repeat(2) {
                c.prepareStatement(OLD_INSERT_MAIL).use { ps ->
                    ps.setString(1, "k-old")
                    ps.setString(2, "subject")
                    ps.setString(3, "body")
                    ps.setTimestamp(4, java.sql.Timestamp.valueOf("2026-10-23 09:00:00"))
                    ps.executeUpdate()
                }
            }
        }

        assertThat(query("SELECT COUNT(*) FROM account_pnl_mail WHERE event_key = 'k-old'") { it.getInt(1) })
            .containsExactly(1)
        assertThat(query("SELECT channel FROM account_pnl_mail WHERE event_key = 'k-old'") { it.getString(1) })
            .containsExactly("EMAIL")
    }

    @Test
    fun `같은 eventKey 의 Discord 행은 들어가고 같은 채널 중복과 CHECK 위반은 거부된다`() {
        migrateLatest()
        insertMail("k-1", "EMAIL")
        insertMail("k-1", "DISCORD", "PNL", "HERE", 3066993)

        assertThat(query("SELECT COUNT(*) FROM account_pnl_mail WHERE event_key = 'k-1'") { it.getInt(1) })
            .containsExactly(2)
        assertThatThrownBy { insertMail("k-1", "DISCORD", "PNL", "HERE", 3066993) }
            .isInstanceOf(SQLIntegrityConstraintViolationException::class.java)
        assertThatThrownBy { insertMail("k-2", "EMAIL", discordTarget = "PNL") }.isCheckViolation()
        assertThatThrownBy { insertMail("k-3", "DISCORD", "PNL", null, 1) }.isCheckViolation()
        assertThatThrownBy { insertMail("k-4", "SMS") }.isCheckViolation()
    }

    @Test
    fun `롤백 SQL 뒤 구 binary claim 은 메일 행만 집는다`() {
        migrateLatest()
        connection().use { c ->
            c.createStatement().use {
                val cols = "(event_key, subject, body, status, attempt_count, channel, discord_target, discord_push, " +
                    "discord_color, locked_by, claim_token, locked_at, created_at, sent_at)"
                val created = "'2026-10-23 09:00:00.000000'"
                val lock = "'worker-1', '11111111-1111-1111-1111-111111111111', '2026-10-23 09:00:30.000000'"
                it.executeUpdate(
                    "INSERT INTO account_pnl_mail $cols VALUES ('k-mail','s','b','PENDING',0,'EMAIL',NULL,NULL,NULL," +
                        "NULL,NULL,NULL,$created,NULL)",
                )
                it.executeUpdate(
                    "INSERT INTO account_pnl_mail $cols VALUES ('k-d1','s','b','PENDING',0,'DISCORD','PNL','HERE',1," +
                        "NULL,NULL,NULL,$created,NULL)",
                )
                it.executeUpdate(
                    "INSERT INTO account_pnl_mail $cols VALUES ('k-d2','s','b','PROCESSING',1,'DISCORD','PNL','HERE',1," +
                        "$lock,$created,NULL)",
                )
                it.executeUpdate(
                    "INSERT INTO account_pnl_mail $cols VALUES ('k-d3','s','b','SENT',1,'DISCORD','PNL','HERE',1," +
                        "NULL,NULL,NULL,$created,'2026-10-23 09:01:00.000000')",
                )
            }
        }
        val before = snapshot()

        val affected = connection().use { c -> c.createStatement().use { it.executeUpdate(ROLLBACK_DISCORD_ROWS) } }

        assertThat(affected).isEqualTo(2)
        val after = snapshot()
        assertThat(after.getValue("k-d1")).isEqualTo("FAILED|0|null|null|null")
        assertThat(after.getValue("k-d2")).isEqualTo("FAILED|1|null|null|null")
        assertThat(after.getValue("k-mail")).isEqualTo(before.getValue("k-mail"))
        assertThat(after.getValue("k-d3")).isEqualTo(before.getValue("k-d3"))

        val claimed = connection().use { c ->
            c.autoCommit = false
            try {
                c.createStatement().use { s ->
                    s.executeQuery(OLD_CLAIM_SELECT.trimIndent()).use { r ->
                        buildList { while (r.next()) add(r.getString("event_key")) }
                    }
                }
            } finally {
                c.rollback()
            }
        }
        assertThat(claimed).containsExactly("k-mail")
    }

    private fun org.assertj.core.api.AbstractThrowableAssert<*, out Throwable>.isCheckViolation() {
        isInstanceOf(SQLException::class.java)
        satisfies({ e ->
            val sql = e as SQLException
            assertThat(sql.errorCode).isEqualTo(3819)
            assertThat(sql.message).contains("ck_account_pnl_mail_channel")
        })
    }

    private fun snapshot(): Map<String, String> =
        connection().use { c ->
            c.createStatement().use { s ->
                s.executeQuery(
                    "SELECT event_key, status, attempt_count, locked_by, claim_token, locked_at, sent_at, " +
                        "channel, discord_target, discord_push, discord_color FROM account_pnl_mail",
                ).use { r ->
                    buildMap {
                        while (r.next()) {
                            val key = r.getString("event_key")
                            val full = (2..11).joinToString("|") { i -> "${r.getString(i)}" }
                            // 롤백 대상 비교용(상태·시도·잠금 3컬럼) 과 전체 비교용을 한 값으로: 전체 문자열은 mail/d3 용.
                            put(key, if (key == "k-d1" || key == "k-d2") lockView(r) else full)
                        }
                    }
                }
            }
        }

    private fun lockView(r: java.sql.ResultSet): String =
        listOf("status", "attempt_count", "locked_by", "claim_token", "locked_at")
            .joinToString("|") { "${r.getString(it)}" }

    private fun insertMail(
        eventKey: String,
        channel: String,
        discordTarget: String? = null,
        discordPush: String? = null,
        discordColor: Int? = null,
    ) {
        connection().use { c: Connection ->
            c.prepareStatement(
                "INSERT INTO account_pnl_mail (event_key, subject, body, created_at, channel, discord_target, " +
                    "discord_push, discord_color) VALUES (?, 's', 'b', '2026-10-23 09:00:00.000000', ?, ?, ?, ?)",
            ).use { ps ->
                ps.setString(1, eventKey)
                ps.setString(2, channel)
                ps.setString(3, discordTarget)
                ps.setString(4, discordPush)
                if (discordColor == null) ps.setNull(5, java.sql.Types.INTEGER) else ps.setInt(5, discordColor)
                ps.executeUpdate()
            }
        }
    }

    private fun migrateLatest() {
        val flyway = flyway()
        flyway.clean()
        assertThat(flyway.migrate().success).isTrue()
    }

    private fun columns(): List<String> =
        query(
            "SELECT CONCAT(COLUMN_NAME, ':', DATA_TYPE, ':', IS_NULLABLE) FROM information_schema.COLUMNS " +
                "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'account_pnl_mail' ORDER BY ORDINAL_POSITION",
        ) { it.getString(1) }

    private fun <T> query(sql: String, map: (java.sql.ResultSet) -> T): List<T> =
        connection().use { c ->
            c.createStatement().use { s ->
                s.executeQuery(sql).use { r -> buildList { while (r.next()) add(map(r)) } }
            }
        }

    private fun indexColumns(indexName: String): List<String> =
        connection().use { connection ->
            connection.metaData.getIndexInfo(mysql.databaseName, null, "account_pnl_mail", false, false).use { result ->
                buildList {
                    while (result.next()) {
                        if (result.getString("INDEX_NAME") == indexName) add(result.getString("COLUMN_NAME"))
                    }
                }
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
        /** base(1bce3dd) JdbcAccountPnlAlertStore.insertMail 의 SQL — channel 을 모른다. */
        const val OLD_INSERT_MAIL = """
INSERT INTO account_pnl_mail (event_key, subject, body, status, attempt_count, created_at)
VALUES (?, ?, ?, 'PENDING', 0, ?)
ON DUPLICATE KEY UPDATE id = id
"""

        /** base(1bce3dd) JdbcAccountPnlAlertStore.claim 의 SELECT — channel 조건 없음. LIMIT 은 바인딩 대신 10 고정. */
        const val OLD_CLAIM_SELECT = """
SELECT id, event_key, subject, body, attempt_count
FROM account_pnl_mail
WHERE status = 'PENDING'
ORDER BY id
LIMIT 10
FOR UPDATE SKIP LOCKED
"""

        /** design §4.5 / runbook `### 배포와 롤백` 의 롤백 SQL. */
        const val ROLLBACK_DISCORD_ROWS = """
UPDATE account_pnl_mail
SET status = 'FAILED',
    claim_token = NULL,
    locked_by = NULL,
    locked_at = NULL
WHERE channel = 'DISCORD'
  AND status IN ('PENDING', 'PROCESSING')
"""

        val mysql: TestDatabase = SharedMySqlContainer.database("premiumspread_owner_alert_channel_migration")
    }
}
