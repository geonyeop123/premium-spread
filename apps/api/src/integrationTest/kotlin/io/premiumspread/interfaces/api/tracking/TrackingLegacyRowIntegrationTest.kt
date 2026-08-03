package io.premiumspread.interfaces.api.tracking

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.get

/**
 * 확정 판정 7필드 중 **하나라도** NULL 이면 fail-closed 여야 한다 (dod.md AC25).
 *
 * 전부 NULL 인 행만 검증하면 `MARKET_SNAPSHOT` 인데 한 컬럼만 빠진 **부분 행**의 fail-open 을 놓친다.
 * 이전 application image 는 status 만 쓰고 신규 컬럼을 모르므로 이런 행이 실제로 생긴다.
 */
class TrackingLegacyRowIntegrationTest : TrackingContractTestBase() {

    @Autowired private lateinit var jdbc: JdbcTemplate

    private val snapshotColumns = listOf(
        "closed_at", "close_observed_at", "close_fx_observed_at",
        "close_korea_price", "close_foreign_price", "close_fx_rate", "close_premium_rate",
    )

    /** 종료 상태로 만들되 지정한 컬럼만 NULL 로 비운다. */
    private fun archiveDirectly(id: Long, source: String?, nullColumn: String? = null) {
        jdbc.update("UPDATE position SET status = 'CLOSED' WHERE id = ?", id)
        if (source == null) return
        val values = mapOf(
            "closed_at" to "NOW(6)",
            "close_observed_at" to "NOW(6)",
            "close_fx_observed_at" to "NOW(6)",
            "close_korea_price" to "129555000",
            "close_foreign_price" to "89500",
            "close_fx_rate" to "1432.6",
            "close_premium_rate" to "1.00",
        )
        val assignments = values.entries.joinToString(", ") { (col, v) ->
            if (col == nullColumn) "$col = NULL" else "$col = $v"
        }
        jdbc.update("UPDATE position SET close_price_source = ?, $assignments WHERE id = ?", source, id)
    }

    private fun grossPnlStatus(id: Long): Int =
        mockMvc.get("/api/v1/trackings/$id/gross-pnl") {
            header("Authorization", "Bearer $token")
        }.andReturn().response.status

    @Test
    fun `L1 - 이전 image 가 종료시킨 전부 NULL 행은 조회는 되고 손익은 409`() {
        val id = saveTracking().id
        archiveDirectly(id, source = null)

        mockMvc.get("/api/v1/trackings/$id") { header("Authorization", "Bearer $token") }
            .andExpect {
                status { isOk() }
                jsonPath("$.status") { value("ARCHIVED") }
                jsonPath("$.closedAt") { doesNotExist() }
                jsonPath("$.hasConfirmedClose") { value(false) }
            }
        assertThat(grossPnlStatus(id)).isEqualTo(409)
    }

    @Test
    fun `L2 - SNAPSHOT_UNAVAILABLE 은 409`() {
        val id = saveTracking().id
        jdbc.update("UPDATE position SET status = 'CLOSED', close_price_source = 'SNAPSHOT_UNAVAILABLE' WHERE id = ?", id)
        assertThat(grossPnlStatus(id)).isEqualTo(409)
    }

    @Test
    fun `L3~L9 - MARKET_SNAPSHOT 이어도 7필드 중 하나만 NULL 이면 409`() {
        snapshotColumns.forEach { column ->
            val id = saveTracking().id
            archiveDirectly(id, source = "MARKET_SNAPSHOT", nullColumn = column)
            assertThat(grossPnlStatus(id))
                .describedAs("%s 가 NULL 인 행은 확정으로 취급하면 안 된다", column)
                .isEqualTo(409)
        }
    }

    @Test
    fun `대조군 - 7필드가 모두 채워지면 확정으로 취급한다`() {
        val id = saveTracking().id
        archiveDirectly(id, source = "MARKET_SNAPSHOT", nullColumn = null)
        assertThat(grossPnlStatus(id)).isEqualTo(200)
    }
}
