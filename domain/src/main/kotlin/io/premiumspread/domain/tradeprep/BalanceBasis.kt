package io.premiumspread.domain.tradeprep

/**
 * 잔고 스냅샷의 신뢰 수준 (design.md D2·D3·D9).
 *
 * - [FRESH] / [STALE] — 검증 가능한 원천(recorded·exchange)에서 읽은 값. 신선도만 다르다.
 * - [UNAVAILABLE] — 원천 조회 실패. 값을 신뢰할 수 없다.
 * - [UNVERIFIED] — owner가 신고한 값(declared). 실계정과 전혀 대조되지 않았다.
 *
 * [VerifiedBalance]는 [FRESH]·[STALE] 스냅샷에서만 만들어진다. [UNVERIFIED] 스냅샷으로는
 * 판정용 잔고에 도달할 수 없다 — 이 파일이 아니라 `VerifiedBalance.from`이 그 경계를 강제한다.
 */
enum class BalanceBasis {
    FRESH,
    STALE,
    UNAVAILABLE,
    UNVERIFIED,
}
