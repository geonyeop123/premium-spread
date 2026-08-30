package io.premiumspread.domain.tradeprep

/**
 * 한 번의 reconcile 이 도달한 상태다 (design.md D17, `dod.md` AC18). 값이 유한해 batch 의 skip
 * 사유·로그로 그대로 쓸 수 있다 (`.ai/rules/batch.md` bounded outcome).
 *
 * 대조하지 못한 사유를 둘로 나눈다. 둘 다 계획을 건드리지 않는다는 결과는 같지만 운영에서
 * 봐야 할 것이 다르다 — [BALANCE_SOURCE_UNAVAILABLE] 은 "아직 배선하지 않았다"(현재 production
 * 의 정상 상태, D22)이고 [BALANCE_UNAVAILABLE] 은 "배선된 원천이 값을 주지 못했다"(장애 신호)다.
 * 하나로 뭉치면 실제 조회 실패가 예정된 미배선 상태에 묻힌다.
 */
enum class TradePreparationReconcileOutcome {
    /**
     * 판정용 잔고 원천([VerifiedBalanceReadPort]) 자체가 배선돼 있지 않다. `ExchangeBalanceAdapter`
     * (`ACT-2` 이후)가 들어오기 전까지 production 이 머무는 정상 상태다 (D22) — Job 이 판정한다.
     */
    BALANCE_SOURCE_UNAVAILABLE,

    /**
     * 원천은 있으나 판정용 잔고를 얻지 못했다(조회 실패·`UNVERIFIED` 강등). 대조하지 못한 것은
     * 불일치를 발견한 것과 다른 사실이므로 계획을 무효화하지 않는다.
     */
    BALANCE_UNAVAILABLE,

    /** 판정용 잔고로 활성 계획들을 실제로 대조했다. */
    RECONCILED,
}

/**
 * [TradePreparationReconcileService.reconcile] 의 결과다. 계획 식별자를 담지 않는다 — 호출자
 * (batch Job)는 개별 계획이 아니라 실행 결과만 알면 된다
 * ([TradePreparationEvaluationSummary] 와 같은 형태).
 */
data class TradePreparationReconcileSummary(
    val outcome: TradePreparationReconcileOutcome,
    val examined: Int = 0,
    val invalidated: Int = 0,
) {
    companion object {
        fun notReconciled(outcome: TradePreparationReconcileOutcome): TradePreparationReconcileSummary {
            require(outcome != TradePreparationReconcileOutcome.RECONCILED) {
                "RECONCILED summary must carry reconcile counts."
            }
            return TradePreparationReconcileSummary(outcome)
        }
    }
}
