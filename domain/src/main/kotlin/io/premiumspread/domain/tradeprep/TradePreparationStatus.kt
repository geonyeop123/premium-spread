package io.premiumspread.domain.tradeprep

/**
 * 거래 준비 계획의 상태 (design.md §2 D4~D23).
 *
 * ```
 * DRAFT ──(registerTarget)──> WATCHING ──(evaluateCondition, verified 결속)──> ARMED
 *   │                             │                                              │
 *   └──────────────(invalidate*)──┴──────────────────────────────────────────────┘
 *                                                                                 ↓
 *                                                                           INVALIDATED
 * ```
 *
 * [INVALIDATED]는 종점이다(D11) — 어떤 경로로도 [ARMED]로 되돌아가지 않는다.
 */
enum class TradePreparationStatus {
    /** `prepare` 산출물. 아직 owner 희망 프리미엄이 없다. */
    DRAFT,

    /** owner 희망 프리미엄이 결속됐다. 조건 평가 대상이다. */
    WATCHING,

    /** 조건 충족 + verified 잔고 결속. 실행 가능(무기한, D15) 상태이며 주문 제출 권한은 아니다. */
    ARMED,

    /** 종점. 체결·owner refresh·reconcile 불일치 사건으로만 도달한다(D4). */
    INVALIDATED,
}
