package io.premiumspread.infrastructure.common.persistence.jpa.tradeprep

import io.premiumspread.domain.market.MarketPair
import io.premiumspread.domain.tradeprep.TradePreparation
import io.premiumspread.domain.tradeprep.TradePreparationRepository
import io.premiumspread.domain.tradeprep.TradePreparationStatus
import org.springframework.stereotype.Repository

@Repository
class JpaTradePreparationRepositoryAdapter(
    private val tradePreparationRepository: SpringDataTradePreparationRepository,
) : TradePreparationRepository {

    override fun save(plan: TradePreparation): TradePreparation = tradePreparationRepository.save(plan)

    override fun findById(id: Long): TradePreparation? =
        tradePreparationRepository.findByIdAndDeletedAtIsNull(id)

    override fun findActiveByOwnerId(ownerId: Long): TradePreparation? =
        tradePreparationRepository.findByOwnerIdAndStatusInAndDeletedAtIsNull(
            ownerId,
            listOf(TradePreparationStatus.WATCHING, TradePreparationStatus.ARMED),
        )

    override fun findAllWatchingByPair(pair: MarketPair): List<TradePreparation> =
        tradePreparationRepository.findAllByStatusAndPair(
            status = TradePreparationStatus.WATCHING,
            symbolCode = pair.symbol.code,
            koreaExchange = pair.koreaExchange,
            foreignExchange = pair.foreignExchange,
        )
}
