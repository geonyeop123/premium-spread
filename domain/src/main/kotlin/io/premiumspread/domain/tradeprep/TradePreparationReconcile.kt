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
 *
 * 생성자는 private 다. outcome 과 카운트의 조합 규칙(어떤 outcome 이 카운트를 갖는가, Domain 이
 * 어떤 outcome 을 주장할 수 있는가)을 [reconciled]·[notReconciled] 두 factory 가 전부 소유한다 —
 * 생성자가 열려 있으면 그 판정을 우회한 조합이 그대로 컴파일된다. `@ConsistentCopyVisibility` 는
 * `copy` 로 나는 같은 우회를 막는다.
 */
@ConsistentCopyVisibility
data class TradePreparationReconcileSummary private constructor(
    val outcome: TradePreparationReconcileOutcome,
    val examined: Int,
    val invalidated: Int,
) {
    companion object {
        /** 실제로 대조한 결과다. 무효화 건수는 대조한 건수를 넘을 수 없다. */
        fun reconciled(examined: Int, invalidated: Int): TradePreparationReconcileSummary {
            require(examined >= 0) { "examined must not be negative, was $examined." }
            require(invalidated in 0..examined) {
                "invalidated must be within 0..$examined, was $invalidated."
            }
            return TradePreparationReconcileSummary(
                TradePreparationReconcileOutcome.RECONCILED,
                examined,
                invalidated,
            )
        }

        /**
         * Domain 이 만들 수 있는 not-reconciled 결과는 [TradePreparationReconcileOutcome.BALANCE_UNAVAILABLE]
         * 하나뿐이라 그 값만 받는다.
         *
         * 배제 목록이 아니라 **허용 값 하나**로 적은 이유: [TradePreparationReconcileOutcome.RECONCILED]
         * 는 카운트를 잃어서 안 되고, [TradePreparationReconcileOutcome.BALANCE_SOURCE_UNAVAILABLE]
         * 은 "빈이 배선되지 않았다"라 Domain 이 관측할 수 없는 사실이다(batch Job 만 안다). 둘은
         * 막는 이유가 다르지만 결론은 같고, 허용 값으로 적으면 outcome 이 늘어날 때 이 판정이
         * 자동으로 보수적인 쪽에 남는다.
         */
        fun notReconciled(outcome: TradePreparationReconcileOutcome): TradePreparationReconcileSummary {
            require(outcome == TradePreparationReconcileOutcome.BALANCE_UNAVAILABLE) {
                "Domain reconcile can only report BALANCE_UNAVAILABLE without counts, was $outcome."
            }
            return TradePreparationReconcileSummary(outcome, examined = 0, invalidated = 0)
        }
    }
}
