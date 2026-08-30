package io.premiumspread.domain.tradeprep

import java.time.Duration
import java.time.Instant

/**
 * 조건 평가가 관측값을 받아들이는 조건이다 (design.md D14, `dod.md` AC17).
 *
 * ```
 * usable = inBounds(premium.observedAt, now, maxAge)   // 0 ≤ now − observedAt ≤ maxAge
 *        && premium.pair == plan.pair
 * ```
 *
 * **양방향 유계다.** `now - observedAt <= maxAge` 만 보면 생산자 clock skew 로 미래가 된
 * `observedAt` 이 음수 age 를 만들어 "신선"으로 통과한다 — Phase 0 에서 실제로 났던 결함이라
 * `TrackingFacade.inBounds` 와 같은 형태를 쓴다.
 *
 * [maxAge] 는 코드 상수가 아니라 설정이다. 값의 근거는 수집 계약("관측값이 10초보다 오래되면
 * seconds 기록을 중단한다", `.ai/rules/batch.md`)이며, 그보다 크게 잡으면 수집이 이미 멈춘
 * 구간의 관측값으로 `ARMED` 에 도달한다.
 *
 * 이 정책은 **premium 관측값의 신선도만** 판정한다. 결속 잔고의 검증 수준(`boundBalanceBasis`)은
 * 별개의 관문이고([TradePreparation.evaluateCondition]) 둘을 섞지 않는다 — premium 이 fresh 해도
 * `UNVERIFIED` 결속이면 관측만 기록한다 (D19).
 */
data class TradePreparationFreshnessPolicy(val maxAge: Duration) {

    init {
        require(!maxAge.isZero && !maxAge.isNegative) { "TradePreparationFreshnessPolicy maxAge must be positive: $maxAge" }
    }

    /**
     * 관측 시각이 `[now - maxAge, now]` 안인지 본다. D14 의 나머지 절반인 `MarketPair` 일치는
     * [TradePreparationEvaluationService] 가 miss 사유를 구분해야 해서 그쪽이 가진다 — 여기서
     * 한 번 더 검사하면 절대 실패하지 않는 분기가 생긴다.
     */
    fun isFresh(observedAt: Instant, now: Instant): Boolean {
        val age = Duration.between(observedAt, now)
        return !age.isNegative && age <= maxAge
    }
}

/**
 * 한 번의 조건 평가가 도달한 상태다. 값이 유한해 batch 의 skip 사유·로그로 그대로 쓸 수 있다
 * (`.ai/rules/batch.md` bounded outcome).
 */
enum class TradePreparationEvaluationOutcome {
    /** 현재 관측값이 없다. `ARMED` 불가이며 계획은 무효화하지 않고 `WATCHING` 으로 남는다 (D14). */
    STREAM_UNAVAILABLE,

    /** 관측값의 `MarketPair` 가 평가 대상 pair 와 다르다. 다른 pair 값으로 보정하지 않는다 (D14). */
    PAIR_MISMATCH,

    /** 관측 시각이 `maxAge` 밖(과거 또는 미래)이다 (D14). */
    STALE_OBSERVATION,

    /** 관측값이 유효해 `WATCHING` 계획들을 실제로 평가했다. */
    EVALUATED,
}

/**
 * [TradePreparationEvaluationService.evaluate] 의 결과다. 계획 식별자를 담지 않는다 — 호출자
 * (batch Job)는 개별 계획이 아니라 실행 결과만 알면 된다.
 */
data class TradePreparationEvaluationSummary(
    val outcome: TradePreparationEvaluationOutcome,
    val evaluated: Int = 0,
    val armed: Int = 0,
    val observedOnly: Int = 0,
) {
    companion object {
        fun notEvaluated(outcome: TradePreparationEvaluationOutcome): TradePreparationEvaluationSummary {
            require(outcome != TradePreparationEvaluationOutcome.EVALUATED) {
                "EVALUATED summary must carry evaluation counts."
            }
            return TradePreparationEvaluationSummary(outcome)
        }
    }
}
