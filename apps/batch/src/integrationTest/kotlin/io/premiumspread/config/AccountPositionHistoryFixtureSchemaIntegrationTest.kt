package io.premiumspread.config

import io.premiumspread.support.BatchIntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Batch `batch-schema.sql` 의 `account_position_episode` 는
 * `infrastructure/common/src/main/resources/db/migration/V24__create_account_position_history.sql` 과
 * `infrastructure/common/src/main/resources/db/migration/V26__add_realized_pnl_settlement.sql` 에서 옮긴 것이다.
 * 두 migration 이 바뀌면(또는 새 migration 이 이 테이블을 바꾸면) 이 목록과 fixture 를 함께 고쳐야 한다.
 */
class AccountPositionHistoryFixtureSchemaIntegrationTest : BatchIntegrationTestBase() {

    @Test
    fun `account_position_episode fixture 는 V24 와 V26 컬럼과 같다`() {
        assertThat(columns("account_position_episode")).containsExactly(
            "id:bigint:NO",
            "status:varchar:NO",
            "opened_at:datetime:NO",
            "closed_at:datetime:YES",
            "korea_quantity:varchar:NO",
            "korea_average_price:varchar:NO",
            "foreign_signed_quantity:varchar:NO",
            "foreign_entry_price:varchar:NO",
            "reference_korea_quantity:varchar:YES",
            "reference_korea_price:varchar:YES",
            "reference_foreign_quantity:varchar:YES",
            "reference_foreign_price:varchar:YES",
            "position_source:varchar:NO",
            "event_count:int:NO",
            "open_marker:tinyint:YES",
            "created_at:datetime:NO",
            "updated_at:datetime:NO",
            "korea_settlement:varchar:YES",
            "foreign_settlement:varchar:YES",
            "korea_realized_krw:decimal:YES",
            "foreign_realized_pnl_usdt:decimal:YES",
            "foreign_commission_usdt:decimal:YES",
            "foreign_funding_usdt:decimal:YES",
            "fx_rate:decimal:YES",
            "fx_observed_at:datetime:YES",
            "total_realized_krw:decimal:YES",
            "settled_seq:int:YES",
            "settled_at:datetime:YES",
            "settlement_attempts:int:YES",
            "settlement_failure:varchar:YES",
            "settlement_next_at:datetime:YES",
            "settlement_target_seq:int:YES",
        )
    }

    @Test
    fun `account_position_episode fixture 의 정산 CHECK 는 V26 과 같다`() {
        assertThat(
            jdbcTemplate.queryForList(
                """
                SELECT CONSTRAINT_NAME FROM information_schema.TABLE_CONSTRAINTS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'account_position_episode'
                  AND CONSTRAINT_TYPE = 'CHECK' AND CONSTRAINT_NAME LIKE '%settlement%'
                ORDER BY CONSTRAINT_NAME
                """.trimIndent(),
                String::class.java,
            ),
        ).containsExactly(
            "ck_account_position_episode_settlement_failure",
            "ck_account_position_episode_settlement_status",
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
