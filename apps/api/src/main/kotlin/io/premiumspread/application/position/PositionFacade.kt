package io.premiumspread.application.position

import io.premiumspread.application.ticker.PremiumFacade
import io.premiumspread.domain.position.Position
import io.premiumspread.domain.position.PositionRepository
import io.premiumspread.domain.ticker.Symbol
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class PositionFacade(
    private val positionRepository: PositionRepository,
    private val premiumFacade: PremiumFacade,
) {

    @Transactional
    fun openPosition(criteria: PositionOpenCriteria): PositionResult {
        val position = Position.create(
            symbol = Symbol(criteria.symbol),
            exchange = criteria.exchange,
            quantity = criteria.quantity,
            entryPrice = criteria.entryPrice,
            entryFxRate = criteria.entryFxRate,
            entryPremiumRate = criteria.entryPremiumRate,
            entryObservedAt = criteria.entryObservedAt,
        )
        val savedPosition = positionRepository.save(position)
        return PositionResult.from(savedPosition)
    }

    @Transactional(readOnly = true)
    fun findById(id: Long): PositionResult? {
        return positionRepository.findById(id)
            ?.let { PositionResult.from(it) }
    }

    @Transactional(readOnly = true)
    fun findAllOpen(): List<PositionResult> {
        return positionRepository.findAllOpen()
            .map { PositionResult.from(it) }
    }

    @Transactional(readOnly = true)
    fun calculatePnl(positionId: Long): PositionPnlResult {
        val position = positionRepository.findById(positionId)
            ?: throw PositionNotFoundException("Position not found: $positionId")

        val currentPremium = premiumFacade.findLatest(position.symbol.code)
            ?: throw PremiumNotFoundException("Premium not found for symbol: ${position.symbol.code}")

        val pnl = position.calculatePremiumDiff(currentPremium.premiumRate)
        return PositionPnlResult.from(positionId, pnl)
    }

    @Transactional
    fun closePosition(positionId: Long): PositionResult {
        val position = positionRepository.findById(positionId)
            ?: throw PositionNotFoundException("Position not found: $positionId")

        position.close()
        val savedPosition = positionRepository.save(position)
        return PositionResult.from(savedPosition)
    }
}

class PositionNotFoundException(message: String) : RuntimeException(message)
class PremiumNotFoundException(message: String) : RuntimeException(message)
