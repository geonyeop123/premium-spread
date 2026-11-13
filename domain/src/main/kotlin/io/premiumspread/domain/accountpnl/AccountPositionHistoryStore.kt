package io.premiumspread.domain.accountpnl

/**
 * 실계정 포지션 이력 저장 port.
 *
 * 계약: 한 트랜잭션에서 열린 건을 잠그고 [PositionEpisodePolicy.decide] 로 건·사건을 반영한 뒤 관측을 0~1행 남긴다.
 * 관측 행 선택: (1) 반영 뒤 열린 건이 있고 `round.pnl != null` → [PositionObservation.holding], 열린 건에 매단다.
 * (2) 열린 건이 없고 reference 가 있는 가장 최근 닫힌 건이 있다 → [PositionObservation.flat], 그 건에 매단다(null 이면 0행).
 * (3) 그 밖 → 0행. 같은 `(episode, observedAt)` 재호출은 행을 늘리지 않는다.
 * 레그 행: HOLDING 관측과 같은 트랜잭션·같은 키로 [PositionHistoryRound.legObservations] 의 레그마다 0~1행 남기고 FLAT 에는 남기지 않는다.
 */
fun interface AccountPositionHistoryStore {
    fun record(round: PositionHistoryRound)
}
