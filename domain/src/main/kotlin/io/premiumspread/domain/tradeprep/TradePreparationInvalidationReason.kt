package io.premiumspread.domain.tradeprep

/**
 * 계획 무효화 트리거 (design.md D4). 셋뿐이다 — 시간 경과는 트리거가 아니며 이 열거형에
 * 시간 기반 값이 없다(AC6).
 */
enum class TradePreparationInvalidationReason {
    /** 이 owner의 tracking이 생성되거나 종료됐다 (D17 — `TrackingFacade`가 같은 트랜잭션에서 호출). */
    TRACKING_EVENT,

    /** owner의 명시 refresh 요청 (D11). */
    OWNER_REFRESH,

    /** 판정용 잔고 스냅샷이 결속된 스냅샷과 달라졌다 (D5·D17 — reconcile Job). */
    RECONCILE_MISMATCH,
}
