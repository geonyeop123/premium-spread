package io.premiumspread.domain.tradeprep

import io.premiumspread.domain.market.MarketPair
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

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

    /**
     * 체결 사건(tracking 생성·종료)으로 이 owner 의 활성 계획을 무효화한다 (design.md D4·D17).
     *
     * `apps:api` 의 `TrackingFacade` 와 (뒤이어) `apps:batch` 의 무효화 경로가 **같은 Domain
     * 서비스**를 쓰도록 여기에 둔다 — 앱 모듈끼리는 서로를 참조할 수 없으므로 전이 로직이 Facade
     * 안에 있으면 batch 가 쓸 경로가 없다 (D21).
     *
     * 기본 전파(`REQUIRED`)라 호출자의 트랜잭션에 참여한다 — D17 이 요구하는 "같은 DB 트랜잭션"이
     * 이 전파로 성립한다. 이벤트나 `@Async` 로 옮기지 않는다.
     *
     * @return 이번 호출로 무효화된 계획. 활성 계획이 없었으면 `null`.
     */
    @Transactional
    fun invalidateActiveOnTrackingEvent(ownerId: Long, at: Instant): TradePreparation? {
        val plan = repository.findActiveByOwnerId(ownerId) ?: return null
        return if (plan.invalidateOnTrackingEvent(at)) repository.save(plan) else null
    }

    @Transactional(readOnly = true)
    fun findAllWatchingByPair(pair: MarketPair): List<TradePreparation> = repository.findAllWatchingByPair(pair)
}
