package io.premiumspread.domain.tradeprep

import io.premiumspread.domain.market.MarketPair
import io.premiumspread.domain.premium.PremiumSnapshot
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * `WATCHING` 계획 조회 · 신선도 판정 · 조건부 전이(arm/관측 기록)를 한 트랜잭션으로 소유하는
 * Domain 서비스다 (design.md D14·D19·D21, `dod.md` AC17).
 *
 * ## 왜 Domain 인가 (D21)
 *
 * 이 세 가지가 `apps:batch` 의 Job 안에 있으면 `apps:api` 는 같은 판정을 쓸 수 없다 — 앱 모듈은
 * 서로를 참조할 수 없기 때문이다. Job 은 premium 읽기 port 와 이 서비스만 주입받아 **조합만**
 * 한다. 전이 규칙 자체는 [TradePreparation.evaluateCondition] 하나이고 이 서비스는 그것을
 * 호출할 자격이 있는 관측값만 통과시킨다.
 *
 * ## `@Service` 가 아닌 이유
 *
 * [freshness] 는 실행 환경(수집 주기)에 묶인 설정값이라 그 값을 소유하는 앱이 빈으로 등록한다.
 * `@Service` 로 두면 `apps:api` 의 component scan 이 이 클래스를 집어 평가 설정이 없는 context 를
 * 부팅 실패시킨다 — `apps:batch` 는 반대로 `io.premiumspread.domain..*Service` 를 스캔에서
 * 제외하므로 애초에 스캔으로는 배선되지 않는다.
 *
 * ## 이 서비스가 하지 않는 것
 *
 * **주문을 제출하지 않는다.** `ARMED` 가 이 단위의 종점이다 (design.md D7).
 * 결속 잔고의 `boundBalanceBasis` 를 다시 계산하거나 승격시키지 않는다 — 결속 시점에 확정된
 * 값을 그대로 신뢰한다 (D19·D20).
 *
 * ## `@Transactional` 이 메서드가 아니라 클래스에 있는 이유
 *
 * kotlin-spring allopen 은 **클래스에 붙은** 애너테이션으로 `final` 을 푼다. `@Service` 가 아닌
 * 이 클래스에서 그 근거는 이 애너테이션뿐이라, 메서드에만 달면 Kotlin final 클래스를 CGLIB 이
 * 상속하지 못해 context 배선 자체가 실패한다.
 */
@Transactional
class TradePreparationEvaluationService(
    private val repository: TradePreparationRepository,
    private val freshness: TradePreparationFreshnessPolicy,
) {

    /**
     * [pair] 의 `WATCHING` 계획을 [premium] 으로 평가한다.
     *
     * [premium] 이 `null` 이면 "현재 관측값 없음"이다 — stream 이 회복되면 재개되도록 계획을
     * **무효화하지 않고** `WATCHING` 으로 남긴다 (D14). 신선하지 않거나 pair 가 다른 관측값도
     * 같다: 평가를 멈출 뿐 상태를 바꾸지 않는다.
     *
     * 조회를 신선도 판정 **뒤에** 두는 것은 의도적이다. 쓸 수 없는 관측값으로는 어떤 계획도
     * 건드리지 않는다는 사실이 코드 순서로 드러난다.
     */
    fun evaluate(pair: MarketPair, premium: PremiumSnapshot?, now: Instant): TradePreparationEvaluationSummary {
        if (premium == null) {
            return TradePreparationEvaluationSummary.notEvaluated(TradePreparationEvaluationOutcome.STREAM_UNAVAILABLE)
        }
        if (premium.pair != pair) {
            return TradePreparationEvaluationSummary.notEvaluated(TradePreparationEvaluationOutcome.PAIR_MISMATCH)
        }
        if (!freshness.isFresh(premium.observedAt, now)) {
            return TradePreparationEvaluationSummary.notEvaluated(TradePreparationEvaluationOutcome.STALE_OBSERVATION)
        }

        var armed = 0
        var observedOnly = 0
        val plans = repository.findAllWatchingByPair(pair)
        plans.forEach { plan ->
            when (plan.evaluateCondition(premium.premiumRate, premium.observedAt)) {
                TradePreparationConditionOutcome.ARMED -> {
                    armed++
                    repository.save(plan)
                }

                TradePreparationConditionOutcome.OBSERVED_ONLY -> {
                    observedOnly++
                    repository.save(plan)
                }

                TradePreparationConditionOutcome.NOT_MET -> Unit
            }
        }

        return TradePreparationEvaluationSummary(
            outcome = TradePreparationEvaluationOutcome.EVALUATED,
            evaluated = plans.size,
            armed = armed,
            observedOnly = observedOnly,
        )
    }
}
