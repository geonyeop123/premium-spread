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
}
