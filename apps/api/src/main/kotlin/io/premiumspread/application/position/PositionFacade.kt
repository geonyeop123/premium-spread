package io.premiumspread.application.position

import io.premiumspread.domain.position.PositionCommand
import io.premiumspread.domain.position.PositionService
import io.premiumspread.domain.premium.PremiumService
import io.premiumspread.domain.ticker.Symbol
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class PositionFacade(
    private val positionService: PositionService,
    private val premiumService: PremiumService,
) {

    @Transactional
    fun openPosition(criteria: PositionOpenCriteria): PositionResult {
        val command = PositionCommand.Create(
            symbol = criteria.symbol,
            exchange = criteria.exchange,
            quantity = criteria.quantity,
            entryPrice = criteria.entryPrice,
            entryFxRate = criteria.entryFxRate,
            entryPremiumRate = criteria.entryPremiumRate,
            entryObservedAt = criteria.entryObservedAt,
        )
        val position = positionService.create(command)
        return PositionResult.from(position)
    }

    @Transactional(readOnly = true)
    fun findById(id: Long): PositionResult? {
        return positionService.findById(id)
            ?.let { PositionResult.from(it) }
    }

    @Transactional(readOnly = true)
    fun findAllOpen(): List<PositionResult> {
        return positionService.findAllOpen()
            .map { PositionResult.from(it) }
    }

    @Transactional(readOnly = true)
    fun calculatePnl(positionId: Long): PositionPnlResult {
        val position = positionService.findById(positionId)
            ?: throw PositionNotFoundException("Position not found: $positionId")

        val currentPremium = premiumService.findLatestBySymbol(Symbol(position.symbol.code))
            ?: throw PremiumNotFoundException("Premium not found for symbol: ${position.symbol.code}")

        val pnl = position.calculatePremiumDiff(currentPremium.premiumRate)
        return PositionPnlResult.from(positionId, pnl)
    }

    @Transactional
    fun closePosition(positionId: Long): PositionResult {
        val position = positionService.findById(positionId)
            ?: throw PositionNotFoundException("Position not found: $positionId")

        position.close()
        val savedPosition = positionService.save(position)
        return PositionResult.from(savedPosition)
    }
}

class PositionNotFoundException(message: String) : RuntimeException(message)
class PremiumNotFoundException(message: String) : RuntimeException(message)
