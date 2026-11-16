package io.premiumspread.infrastructure.common.persistence.jdbc.accountpnl

import io.premiumspread.infrastructure.common.support.LatestSchemaFixture
import io.premiumspread.infrastructure.common.support.SharedMySqlContainer
import io.premiumspread.infrastructure.common.support.TestDatabase
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.sql.Timestamp
import java.time.Instant
import javax.sql.DataSource

@Tag("integration")
class JdbcAccountObservationRetentionStoreIntegrationTest {

    private lateinit var dataSource: DataSource
    private lateinit var transactionManager: DataSourceTransactionManager
    private lateinit var jdbcTemplate: JdbcTemplate
    private lateinit var store: JdbcAccountObservationRetentionStore

    @BeforeEach
    fun setUp() {
        latestSchema.reset()
        dataSource = DriverManagerDataSource(mysql.utcJdbcUrl, mysql.username, mysql.password)
        transactionManager = DataSourceTransactionManager(dataSource)
        jdbcTemplate = JdbcTemplate(dataSource)
        store = JdbcAccountObservationRetentionStore(jdbcTemplate, transactionManager)
    }

    @Test
    fun `cutoff 이전 관측과 그 레그만 지우고 cutoff 이후와 건 사건은 남는다`() {
        val episode = insertEpisode()
        insertHoldingWithLegs(episode, CUTOFF.minusSeconds(120))
        insertHoldingWithLegs(episode, CUTOFF.minusNanos(1_000))
        insertHoldingWithLegs(episode, CUTOFF)
        insertHoldingWithLegs(episode, CUTOFF.plusSeconds(60))

        val deleted = store.deleteObservedBefore(CUTOFF, 100)

        assertThat(deleted).isEqualTo(2)
        val kept = listOf(CUTOFF, CUTOFF.plusSeconds(60))
        assertThat(observedAts("account_pnl_observation")).containsExactlyElementsOf(kept)
        assertThat(observedAts("account_foreign_leg_observation")).containsExactlyElementsOf(kept)
        assertThat(observedAts("account_korea_leg_observation")).containsExactlyElementsOf(kept)
        assertThat(count("SELECT COUNT(*) FROM account_position_episode")).isEqualTo(1)
        assertThat(count("SELECT COUNT(*) FROM account_position_event")).isEqualTo(1)
    }

    @Test
    fun `limit 만큼 오래된 순으로 지우고 반환값은 지운 관측 수다`() {
        val first = insertEpisode(closed = true)
        insertHoldingWithLegs(first, CUTOFF.minusSeconds(300))
        insertHoldingWithLegs(first, CUTOFF.minusSeconds(180))
        val second = insertEpisode()
        insertHoldingWithLegs(second, CUTOFF.minusSeconds(240))
        insertHoldingWithLegs(second, CUTOFF.minusSeconds(60))

        assertThat(store.deleteObservedBefore(CUTOFF, 2)).isEqualTo(2)

        val remaining = listOf(CUTOFF.minusSeconds(180), CUTOFF.minusSeconds(60))
        assertThat(observedAts("account_pnl_observation")).containsExactlyElementsOf(remaining)
        assertThat(observedAts("account_foreign_leg_observation")).containsExactlyElementsOf(remaining)
        assertThat(observedAts("account_korea_leg_observation")).containsExactlyElementsOf(remaining)

        assertThat(store.deleteObservedBefore(CUTOFF, 2)).isEqualTo(2)
        assertThat(store.deleteObservedBefore(CUTOFF, 2)).isEqualTo(0)
        assertThat(count("SELECT COUNT(*) FROM account_pnl_observation")).isEqualTo(0)
        assertThat(count("SELECT COUNT(*) FROM account_foreign_leg_observation")).isEqualTo(0)
        assertThat(count("SELECT COUNT(*) FROM account_korea_leg_observation")).isEqualTo(0)
        assertThat(count("SELECT COUNT(*) FROM account_position_episode")).isEqualTo(2)
        assertThat(count("SELECT COUNT(*) FROM account_position_event")).isEqualTo(2)
    }

    @Test
    fun `레그가 없는 FLAT 관측도 지운다`() {
        val episode = insertEpisode(closed = true)
        insertFlat(episode, CUTOFF.minusSeconds(60))
        insertFlat(episode, CUTOFF.plusSeconds(60))

        assertThat(store.deleteObservedBefore(CUTOFF, 100)).isEqualTo(1)

        assertThat(observedAts("account_pnl_observation")).containsExactly(CUTOFF.plusSeconds(60))
    }

    @Test
    fun `지울 것이 없으면 0 이다`() {
        assertThat(store.deleteObservedBefore(CUTOFF, 100)).isEqualTo(0)

        val episode = insertEpisode()
        insertHoldingWithLegs(episode, CUTOFF)

        assertThat(store.deleteObservedBefore(CUTOFF, 100)).isEqualTo(0)
        assertThat(count("SELECT COUNT(*) FROM account_pnl_observation")).isEqualTo(1)
        assertThat(count("SELECT COUNT(*) FROM account_foreign_leg_observation")).isEqualTo(1)
        assertThat(count("SELECT COUNT(*) FROM account_korea_leg_observation")).isEqualTo(1)
    }

    @Test
    fun `관측 DELETE 가 실패하면 같은 배치의 레그 DELETE 도 롤백된다`() {
        val episode = insertEpisode()
        insertHoldingWithLegs(episode, CUTOFF.minusSeconds(120))
        insertHoldingWithLegs(episode, CUTOFF.minusSeconds(60))
        val failing = ObservationDeleteFailingJdbcTemplate(dataSource)
        val failingStore = JdbcAccountObservationRetentionStore(failing, transactionManager)

        assertThatThrownBy { failingStore.deleteObservedBefore(CUTOFF, 100) }
            .isInstanceOf(DataAccessResourceFailureException::class.java)

        // 바이낸스 2행 + 빗썸 2행이 관측 DELETE 전에 실제로 지워졌다가 롤백됐다
        assertThat(failing.legDeletes).isEqualTo(4)
        assertThat(count("SELECT COUNT(*) FROM account_pnl_observation")).isEqualTo(2)
        assertThat(count("SELECT COUNT(*) FROM account_foreign_leg_observation")).isEqualTo(2)
        assertThat(count("SELECT COUNT(*) FROM account_korea_leg_observation")).isEqualTo(2)
    }

    @Test
    fun `limit 이 1 미만이면 거부한다`() {
        assertThatThrownBy { store.deleteObservedBefore(CUTOFF, 0) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    /**
     * 관측 DELETE 만 실패시키는 위임 래퍼. 같은 DataSource 라 트랜잭션 연결을 공유한다.
     * 레그 DELETE 가 실제로 먼저 실행됐는지 [legDeletes] 로 센다(롤백 검증이 공허해지지 않도록).
     */
    private class ObservationDeleteFailingJdbcTemplate(dataSource: DataSource) : JdbcTemplate(dataSource) {
        var legDeletes = 0

        override fun update(sql: String, vararg args: Any?): Int {
            if (sql.contains("DELETE FROM account_pnl_observation")) {
                throw DataAccessResourceFailureException("injected observation delete failure")
            }
            val affected = super.update(sql, *args)
            if (sql.contains("_leg_observation")) {
                legDeletes += affected
            }
            return affected
        }
    }

    /** 건 1행 + OPENED 사건 1행. 값은 스키마 제약만 맞춘 고정 fixture 다. */
    private fun insertEpisode(closed: Boolean = false): Long {
        jdbcTemplate.update(
            """
            INSERT INTO account_position_episode (
                status, opened_at, closed_at, korea_quantity, korea_average_price,
                foreign_signed_quantity, foreign_entry_price, position_source, event_count, created_at, updated_at
            ) VALUES (?, ?, ?, '0.2', '113016278', '-0.2', '83549.4', 'EXCHANGE_API', 1, ?, ?)
            """.trimIndent(),
            if (closed) "CLOSED" else "OPEN",
            ts(BASE),
            if (closed) ts(BASE) else null,
            ts(BASE),
            ts(BASE),
        )
        val id = jdbcTemplate.queryForObject("SELECT MAX(id) FROM account_position_episode", Long::class.java)!!
        jdbcTemplate.update(
            """
            INSERT INTO account_position_event (
                episode_id, seq, event_type, occurred_at, korea_quantity, korea_average_price,
                foreign_signed_quantity, foreign_entry_price, hedged, position_source, created_at
            ) VALUES (?, 1, 'OPENED', ?, '0.2', '113016278', '-0.2', '83549.4', TRUE, 'EXCHANGE_API', ?)
            """.trimIndent(),
            id,
            ts(BASE),
            ts(BASE),
        )
        return id
    }

    private fun insertHoldingWithLegs(episodeId: Long, observedAt: Instant) {
        insertObservation(episodeId, observedAt, holding = true)
        jdbcTemplate.update(
            """
            INSERT INTO account_foreign_leg_observation (
                episode_id, observed_at, leverage, margin_type, position_source, created_at
            ) VALUES (?, ?, 3, 'ISOLATED', 'EXCHANGE_API', ?)
            """.trimIndent(),
            episodeId,
            ts(observedAt),
            ts(observedAt),
        )
        jdbcTemplate.update(
            """
            INSERT INTO account_korea_leg_observation (episode_id, observed_at, entry_fee_krw, created_at)
            VALUES (?, ?, 9041.3022, ?)
            """.trimIndent(),
            episodeId,
            ts(observedAt),
            ts(observedAt),
        )
    }

    private fun insertFlat(episodeId: Long, observedAt: Instant) =
        insertObservation(episodeId, observedAt, holding = false)

    private fun insertObservation(episodeId: Long, observedAt: Instant, holding: Boolean) {
        jdbcTemplate.update(
            """
            INSERT INTO account_pnl_observation (
                episode_id, kind, observed_at, net_percent, net_krw, current_premium_percent, entry_premium_percent,
                premium_gap_percent_point, fx_rate, korea_exit_price, foreign_exit_price, created_at
            ) VALUES (?, ?, ?, ?, ?, 1.50, 1.20, 0.30, 1328.5, 109500000, 81580, ?)
            """.trimIndent(),
            episodeId,
            if (holding) "HOLDING" else "FLAT",
            ts(observedAt),
            if (holding) "0.12" else null,
            if (holding) "12345" else null,
            ts(observedAt),
        )
    }

    private fun observedAts(table: String): List<Instant> = jdbcTemplate
        .queryForList("SELECT observed_at FROM $table ORDER BY observed_at", Timestamp::class.java)
        .map { it.toInstant() }

    private fun count(sql: String): Int = jdbcTemplate.queryForObject(sql, Int::class.java)!!

    private fun ts(instant: Instant): Timestamp = Timestamp.from(instant)

    companion object {
        private val BASE = Instant.parse("2025-01-01T00:00:00Z")
        private val CUTOFF = Instant.parse("2025-11-10T00:30:00Z")

        val mysql: TestDatabase = SharedMySqlContainer.database("premiumspread_account_observation_retention")
        private val latestSchema = LatestSchemaFixture(mysql)
    }
}
