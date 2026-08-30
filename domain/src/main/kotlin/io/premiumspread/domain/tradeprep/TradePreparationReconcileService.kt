package io.premiumspread.domain.tradeprep

import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * 활성 계획의 결속 잔고 스냅샷과 **현재 판정용 잔고**를 대조해 불일치를 무효화하는 Domain
 * 서비스다 (design.md D5·D17, `dod.md` AC18).
 *
 * ## 왜 Domain 인가 (D21)
 *
 * 무효화 producer 는 셋이다 — 체결(`apps:api` 의 `TrackingFacade`), owner refresh(`apps:api`),
 * 주기 reconcile(`apps:batch`). 전이 규칙이 어느 한 앱 안에 있으면 나머지 앱은 같은 판정을 쓸 수
 * 없다. 규칙 자체는 [TradePreparation.invalidateOnReconcileMismatch] 하나이고, 이 서비스는 대상
 * 조회와 트랜잭션 경계만 얹는다.
 *
 * ## 대조하지 못한 것과 불일치를 발견한 것은 다른 사실이다
 *
 * 판정용 잔고를 얻지 못하면 **아무 계획도 건드리지 않는다.** "못 읽었으니 안전하게 무효화"는
 * fail-closed 처럼 보이지만 틀렸다 — owner 가 등록한 계획이 원천 미배선(D22)만으로 매 주기
 * 사라진다. fail-closed 경계는 이미 `ARMED` 전이에 있다 (D19).
 *
 * ## `@Service` 가 아니라 `@Transactional` 인 이유
 *
 * [TradePreparationEvaluationService] 와 같다. `apps:batch` 는
 * `io.premiumspread.domain..*Service` 를 component scan 에서 제외하므로 스캔으로는 배선되지 않고
 * batch `@Configuration` 이 빈으로 만든다. kotlin-spring allopen 은 **클래스에 붙은** 애너테이션
 * 으로 `final` 을 푸므로 이 애너테이션을 메서드로 내리면 CGLIB 프록시가 만들어지지 않아 배선이
 * 실패한다.
 */
@Transactional
class TradePreparationReconcileService(private val repository: TradePreparationRepository) {

    /**
     * [balance] 와 결속 스냅샷 id 가 다른 활성 계획을 `INVALIDATED` 로 전이시킨다.
     *
     * [balance] 가 `null` 이면 "현재 판정용 잔고 없음"이다 — 계획을 **무효화하지 않고** 그대로
     * 남긴다. 원천이 회복되면 다음 실행이 그대로 재개한다 (D14 의 stream 부재 처리와 같은 형태).
     *
     * 조회를 잔고 판정 **뒤에** 두는 것은 의도적이다. 대조할 상대가 없으면 어떤 계획도 읽지
     * 않는다는 사실이 코드 순서로 드러난다.
     */
    fun reconcile(balance: VerifiedBalance?, now: Instant): TradePreparationReconcileSummary {
        if (balance == null) {
            return TradePreparationReconcileSummary.notReconciled(TradePreparationReconcileOutcome.BALANCE_UNAVAILABLE)
        }

        var invalidated = 0
        val plans = repository.findAllActive()
        plans.forEach { plan ->
            // 일치하면 false 다 — 상태를 바꾸지 않고 save 도 하지 않는다 (AC5).
            if (plan.invalidateOnReconcileMismatch(balance.snapshotId, now)) {
                invalidated++
                repository.save(plan)
            }
        }

        return TradePreparationReconcileSummary(
            outcome = TradePreparationReconcileOutcome.RECONCILED,
            examined = plans.size,
            invalidated = invalidated,
        )
    }
}
