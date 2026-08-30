package io.premiumspread.domain.tradeprep

import io.premiumspread.domain.market.MarketPair

/**
 * `TradePreparation` 영속 계약이다. D11의 조건부 전이(`WHERE id=? AND version=? AND status=?`)의
 * 실제 SQL은 infrastructure adapter(T4)가 구성한다 — 이 인터페이스는 Domain이 소유해야 할
 * capability 경계만 정의한다(`.ai/rules/architecture.md` Port와 Adapter).
 */
interface TradePreparationRepository {
    fun save(plan: TradePreparation): TradePreparation

    fun findById(id: Long): TradePreparation?

    /** owner당 활성(`WATCHING`·`ARMED`) 계획은 최대 하나다 (D16·D23). */
    fun findActiveByOwnerId(ownerId: Long): TradePreparation?

    /** 조건 평가 대상 조회 — evaluation Job(T7)이 pair 단위로 순회한다 (D21). */
    fun findAllWatchingByPair(pair: MarketPair): List<TradePreparation>

    /**
     * reconcile 대상 조회 — 활성(`WATCHING`·`ARMED`) 계획 전체다 (D17, T8).
     *
     * **pair 로 좁히지 않는다.** 대조 대상인 판정용 잔고([VerifiedBalanceReadPort])는 `MarketPair`
     * 를 갖지 않는 계정 단위 값이라, 어느 pair 의 계획이든 같은 결속 스냅샷을 참조한다. batch
     * runtime 이 한 번에 수집하는 pair 로 좁히면 다른 pair 의 계획만 조용히 대조에서 빠진다.
     *
     * 구현은 결정적 순서를 보장한다 — 한 사이클이 여러 계획을 한 트랜잭션에서 전이시키므로
     * 순서가 인덱스 선택에 좌우되면 실패 시 롤백 범위가 실행마다 달라진다
     * ([findAllWatchingByPair] 와 같은 이유).
     */
    fun findAllActive(): List<TradePreparation>
}
