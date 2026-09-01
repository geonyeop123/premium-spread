package io.premiumspread.infrastructure.common.persistence.jpa.tradeprep

import io.premiumspread.domain.market.MarketPair
import io.premiumspread.domain.tradeprep.TradePreparation
import io.premiumspread.domain.tradeprep.TradePreparationRepository
import io.premiumspread.domain.tradeprep.TradePreparationStatus
import org.springframework.stereotype.Repository

@Repository
class JpaTradePreparationRepositoryAdapter(private val tradePreparationRepository: SpringDataTradePreparationRepository) :
    TradePreparationRepository {

    /**
     * `saveAndFlush` 다. 한 트랜잭션에서 기존 `WATCHING` 계획을 무효화한 뒤 새 계획을 승격하는
     * 경로(design.md D11·D23)는 두 UPDATE 의 **순서**가 곧 정합성이다 — 커밋 시점에 한꺼번에
     * flush 하면 Hibernate 가 두 UPDATE 를 어떤 순서로 내보낼지 보장하지 않아,
     * `uk_trade_preparation_owner_active` 가 승격 UPDATE 를 먼저 보면 정상 경로가 유일성 위반으로
     * 떨어진다. 호출 순서대로 flush 해 그 창을 없앤다.
     *
     * 부수 효과로 제약 위반이 Facade 메서드 **안에서** `DataIntegrityViolationException` 으로
     * 드러나 안정된 Application error 로 변환할 수 있다 (`JpaMemberRepositoryAdapter` 와 같은 패턴).
     */
    override fun save(plan: TradePreparation): TradePreparation = tradePreparationRepository.saveAndFlush(plan)

    override fun findById(id: Long): TradePreparation? =
        tradePreparationRepository.findByIdAndDeletedAtIsNull(id)

    override fun findActiveByOwnerId(ownerId: Long): TradePreparation? =
        tradePreparationRepository.findByOwnerIdAndStatusInAndDeletedAtIsNull(ownerId, ACTIVE_STATUSES)

    override fun findAllActive(): List<TradePreparation> =
        tradePreparationRepository.findAllByStatusInAndDeletedAtIsNullOrderByIdAsc(ACTIVE_STATUSES)

    override fun findAllWatchingByPair(pair: MarketPair): List<TradePreparation> =
        tradePreparationRepository.findAllByStatusAndPair(
            status = TradePreparationStatus.WATCHING,
            symbolCode = pair.symbol.code,
            koreaExchange = pair.koreaExchange,
            foreignExchange = pair.foreignExchange,
        )

    private companion object {
        /**
         * `TradePreparation.isActive` 와 `uk_trade_preparation_owner_active(active_key)` 가 쓰는
         * 것과 같은 정의다. 세 정의가 어긋나면 DB 가 막는 것, 도메인이 활성이라 부르는 것,
         * reconcile 이 대조하는 것이 서로 달라진다.
         */
        val ACTIVE_STATUSES = listOf(TradePreparationStatus.WATCHING, TradePreparationStatus.ARMED)
    }
}
