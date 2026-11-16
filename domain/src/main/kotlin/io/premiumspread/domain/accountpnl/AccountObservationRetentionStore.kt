package io.premiumspread.domain.accountpnl

import java.time.Instant

/**
 * 보존 기간이 지난 매분 관측 이력 삭제 port (#148).
 *
 * 계약: 한 번 호출 = 한 트랜잭션이다. `observed_at < cutoff` 인 관측(HOLDING·FLAT)을 오래된 순(`observed_at, id`)으로
 * 최대 [limit] 건 고르고, 그 관측 키 `(episode_id, observed_at)` 의 바이낸스·빗썸 레그 행을 먼저 지운 뒤 관측 행을 지운다.
 * 어느 문장이 실패하면 그 배치 전체가 롤백된다. `observed_at = cutoff` 인 행은 남긴다.
 * 건·사건 행은 지우지 않는다. 반환값은 지운 관측 행 수다(레그 행 수는 세지 않는다).
 */
fun interface AccountObservationRetentionStore {
    fun deleteObservedBefore(cutoff: Instant, limit: Int): Int
}
