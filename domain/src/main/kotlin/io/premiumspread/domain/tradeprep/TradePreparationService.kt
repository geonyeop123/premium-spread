package io.premiumspread.domain.tradeprep

import io.premiumspread.domain.market.MarketPair
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * `TradePreparation` 조회·저장의 Domain 진입점이다. `apps:api` Facade(T5)와 `apps:batch`
 * `TradePreparationEvaluationJob`(T7)이 이 서비스만 통해 계획을 다룬다(D21) — 둘 다 같은 Domain
 * 서비스를 쓰므로 상태 전이 로직을 중복 구현하지 않는다.
 */
@Service
class TradePreparationService(private val repository: TradePreparationRepository) {

    @Transactional
    fun create(spec: TradePreparationSpec): TradePreparation = repository.save(TradePreparation.create(spec))

    @Transactional
    fun save(plan: TradePreparation): TradePreparation = repository.save(plan)

    @Transactional(readOnly = true)
    fun findById(id: Long): TradePreparation? = repository.findById(id)

    /** 남의 계획 조회는 owner-scoped다(D10) — 존재를 노출하지 않도록 호출자가 404로 변환한다. */
    @Transactional(readOnly = true)
    fun findByIdAndOwnerId(id: Long, ownerId: Long): TradePreparation? {
        val plan = repository.findById(id) ?: return null
        return if (plan.ownerId == ownerId) plan else null
    }

    @Transactional(readOnly = true)
    fun findActiveByOwnerId(ownerId: Long): TradePreparation? = repository.findActiveByOwnerId(ownerId)

    @Transactional(readOnly = true)
    fun findAllWatchingByPair(pair: MarketPair): List<TradePreparation> = repository.findAllWatchingByPair(pair)
}
