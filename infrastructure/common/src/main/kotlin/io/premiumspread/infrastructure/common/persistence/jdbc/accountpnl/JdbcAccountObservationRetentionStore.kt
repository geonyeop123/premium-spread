package io.premiumspread.infrastructure.common.persistence.jdbc.accountpnl

import io.premiumspread.domain.accountpnl.AccountObservationRetentionStore
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Instant

/**
 * 보존 기간이 지난 매분 관측 이력 삭제 (#148, design D1·D3).
 *
 * [deleteObservedBefore] 한 번은 한 트랜잭션이다.
 * 1. `observed_at < cutoff` 관측을 `ORDER BY observed_at, id LIMIT ?` 로 고른다(`ix_account_pnl_observation_observed_at`).
 * 2. 고른 관측 키 `(episode_id, observed_at)` 로 바이낸스·빗썸 레그 행을 지운다(PK) — FK 때문에 관측보다 먼저다.
 * 3. 고른 `id` 로 관측을 지운다. 어느 문장이 실패해도 배치 전체가 롤백된다.
 *
 * 고른 행의 `observed_at` 은 읽은 [Timestamp] 를 그대로 다시 바인딩해 µs 정밀도와 시간대 변환이 왕복에서 같다.
 * 모니터는 최근 시각 행만 INSERT 하므로 삭제 범위와 겹치지 않아 `FOR UPDATE` 는 두지 않는다.
 * 건·사건 테이블은 건드리지 않는다. 로그는 Job 이 남긴다(여기서는 값을 다루지 않는다).
 */
@Repository
class JdbcAccountObservationRetentionStore(
    private val jdbcTemplate: JdbcTemplate,
    transactionManager: PlatformTransactionManager,
) : AccountObservationRetentionStore {

    private val transaction = TransactionTemplate(transactionManager)

    override fun deleteObservedBefore(cutoff: Instant, limit: Int): Int {
        require(limit >= 1) { "limit must be at least 1." }
        return transaction.execute { deleteBatch(Timestamp.from(cutoff), limit) } ?: 0
    }

    private fun deleteBatch(cutoff: Timestamp, limit: Int): Int {
        val targets = jdbcTemplate.query(SELECT_EXPIRED, TARGET_MAPPER, cutoff, limit)
        if (targets.isEmpty()) return 0

        val keyPlaceholders = targets.joinToString(", ") { "(?, ?)" }
        val keyArgs = targets.flatMap { listOf(it.episodeId, it.observedAt) }.toTypedArray()
        jdbcTemplate.update(
            "DELETE FROM account_foreign_leg_observation WHERE (episode_id, observed_at) IN ($keyPlaceholders)",
            *keyArgs,
        )
        jdbcTemplate.update(
            "DELETE FROM account_korea_leg_observation WHERE (episode_id, observed_at) IN ($keyPlaceholders)",
            *keyArgs,
        )

        val idPlaceholders = targets.joinToString(", ") { "?" }
        val idArgs = targets.map { it.id }.toTypedArray()
        return jdbcTemplate.update("DELETE FROM account_pnl_observation WHERE id IN ($idPlaceholders)", *idArgs)
    }

    private data class Target(val id: Long, val episodeId: Long, val observedAt: Timestamp)

    private companion object {
        const val SELECT_EXPIRED =
            "SELECT id, episode_id, observed_at FROM account_pnl_observation " +
                "WHERE observed_at < ? ORDER BY observed_at, id LIMIT ?"

        val TARGET_MAPPER = RowMapper { rs, _ ->
            Target(rs.getLong("id"), rs.getLong("episode_id"), rs.getTimestamp("observed_at"))
        }
    }
}
