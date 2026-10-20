package io.premiumspread.config

import io.premiumspread.support.BatchIntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Batch 는 Flyway 를 쓰지 않고 `batch-schema.sql` fixture 로 스키마를 만든다. 아래 기대 목록은
 * `infrastructure/common/src/main/resources/db/migration/V18__create_account_pnl_alert.sql` 에서 옮긴 것이다.
 * V18 이 바뀌면(또는 새 migration 이 이 테이블을 바꾸면) 이 목록과 fixture 를 함께 고쳐야 한다.
 */
class AccountPnlFixtureSchemaIntegrationTest : BatchIntegrationTestBase() {

    @Test
    fun `account_pnl_monitor_state fixture 는 V18 컬럼과 같다`() {
        assertThat(columns("account_pnl_monitor_state")).containsExactly(
            "id:tinyint:NO",
            "in_episode:tinyint:NO",
            "position_fingerprint:varchar:YES",
            "last_sent_at:datetime:YES",
            "last_success_at:datetime:YES",
            "monitoring_since:datetime:YES",
            "outage_notified:tinyint:NO",
            "version:bigint:NO",
            "updated_at:datetime:NO",
        )
    }

    @Test
    fun `account_pnl_mail fixture 는 V18 컬럼과 같다`() {
        assertThat(columns("account_pnl_mail")).containsExactly(
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
        )
    }

    private fun columns(table: String): List<String> = jdbcTemplate.query(
        """
        SELECT COLUMN_NAME, DATA_TYPE, IS_NULLABLE FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? ORDER BY ORDINAL_POSITION
        """.trimIndent(),
        { rs, _ -> "${rs.getString(1)}:${rs.getString(2).lowercase()}:${rs.getString(3)}" },
        table,
    )
}
