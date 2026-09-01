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
     * [source] 의 현재 판정용 잔고와 결속 스냅샷 id 가 다른 활성 계획을 `INVALIDATED` 로
     * 전이시킨다.
     *
     * ## 계획을 잔고보다 **먼저** 읽는다 — 이 순서가 정확성의 전부다
     *
     * 반대로 하면 갓 등록한 멀쩡한 계획이 죽는다: 잔고 `S1` 을 읽고 → `registerTarget` 이 `S2`
     * 결속 계획을 커밋하고 → 이 트랜잭션이 그 계획을 보고 `S1 ≠ S2` 라 무효화한다.
     *
     * **트랜잭션 안에 넣는 것만으로는 막히지 않는다.** REPEATABLE READ 의 consistent read view 는
     * `BEGIN` 이 아니라 **첫 consistent read** 에서 열리고, Spring 은
     * `START TRANSACTION WITH CONSISTENT SNAPSHOT` 을 쓰지 않는다. [source] 는 거래소·기록 원천
     * 이지 DB 읽기가 아니므로 view 를 열지 않는다 — 그래서 잔고를 먼저 읽어도 view 는 여전히
     * [TradePreparationRepository.findAllActive] 에서 열리고, 그 사이 커밋된 계획이 **보이면서**
     * 그보다 앞서 읽은 잔고와 대조된다. 위 순서가 그대로 성립한다.
     *
     * 계획을 먼저 읽으면 모든 교차에서 안전하다.
     *
     * - 조회 **뒤에** 커밋된 계획은 이 트랜잭션의 view 에 없다 → 이번 사이클이 건너뛰고 다음
     *   사이클이 잡는다. 무효화가 늦어질 뿐 틀리지 않는다
     * - 보이는 계획은 전부 잔고 읽기보다 **앞서** 존재했다 → 불일치면 결속 뒤에 잔고가 실제로
     *   바뀐 것이므로 진짜 불일치다
     *
     * 대가는 원천이 죽었을 때 `SELECT` 한 번이 헛도는 것뿐이다. 경합과 바꿀 값어치가 없다.
     *
     * 잔고가 `null` 이면 "현재 판정용 잔고 없음"이다 — 계획을 **무효화하지 않고** 그대로 남긴다.
     * 원천이 회복되면 다음 실행이 그대로 재개한다 (D14 의 stream 부재 처리와 같은 형태).
     */
    fun reconcile(source: VerifiedBalanceReadPort, now: Instant): TradePreparationReconcileSummary {
        // ① 계획 먼저. 이 문장이 read view 를 연다 — 이보다 뒤에 커밋된 계획은 보이지 않는다.
        val plans = repository.findAllActive()

        // ② 그 다음 잔고. 여기서 읽은 값은 위에서 본 계획 전부보다 나중이라 대조가 성립한다.
        //
        //    ACT-2 의 `ExchangeBalanceAdapter` 가 들어오면 이 한 줄이 거래소 HTTPS 왕복이 되고,
        //    그 지연 내내 DB 커넥션을 붙잡는다. 지금은 구현이 0개(D22)이거나 테스트 fake 라
        //    무해하지만, 실어댑터를 배선할 때는 "계획은 트랜잭션 안 · 잔고는 밖 · 대조는 짧은
        //    두 번째 트랜잭션" 형태로 나눠야 할 수 있다. 그때도 위 순서(계획 먼저)는 유지한다.
        val balance = source.findForDecision()
            ?: return TradePreparationReconcileSummary.notReconciled(
                TradePreparationReconcileOutcome.BALANCE_UNAVAILABLE,
            )

        var invalidated = 0
        plans.forEach { plan ->
            // 일치하면 false 다 — 상태를 바꾸지 않고 save 도 하지 않는다 (AC5).
            if (plan.invalidateOnReconcileMismatch(balance.snapshotId, now)) {
                invalidated++
                repository.save(plan)
            }
        }

        return TradePreparationReconcileSummary.reconciled(examined = plans.size, invalidated = invalidated)
    }
}
