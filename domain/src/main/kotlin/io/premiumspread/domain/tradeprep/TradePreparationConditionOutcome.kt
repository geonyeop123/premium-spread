package io.premiumspread.domain.tradeprep

/** [TradePreparation.evaluateCondition]의 결과 (design.md D7·D14·D19). */
enum class TradePreparationConditionOutcome {
    /** 조건 미충족. 상태는 그대로 `WATCHING`이다. */
    NOT_MET,

    /**
     * 조건은 충족했지만 `UNVERIFIED` 결속이라 `ARMED`로 전이하지 않는다 — `conditionFirstMetAt`과
     * 당시 프리미엄만 기록하는 권한 없는 관측이다(D19). `WATCHING`을 유지한다.
     */
    OBSERVED_ONLY,

    /** 조건 충족 + verified(`FRESH`/`STALE`) 결속. `ARMED`로 전이했다. */
    ARMED,
}
