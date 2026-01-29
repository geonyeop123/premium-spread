package io.premiumspread.application.position

import io.premiumspread.domain.position.PositionCommand
import io.premiumspread.domain.position.PositionService
import io.premiumspread.domain.premium.PremiumService
import io.premiumspread.domain.ticker.Symbol
import io.premiumspread.infrastructure.cache.PositionCacheWriter
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class PositionFacade(
    private val positionService: PositionService,
    private val premiumService: PremiumService,
    private val positionCacheWriter: PositionCacheWriter,
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

        // 포지션 캐시 갱신
        updatePositionCache()

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

        // 포지션 캐시 갱신
        updatePositionCache()

        return PositionResult.from(savedPosition)
    }

    /**
     * 포지션 캐시 갱신 (배치 서버의 조건부 캐싱용)
     */
    private fun updatePositionCache() {
        val openPositions = positionService.findAllOpen()
        val hasOpen = openPositions.isNotEmpty()
        val count = openPositions.size

        positionCacheWriter.updateOpenPositionStatus(hasOpen, count)
    }
}

class PositionNotFoundException(message: String) : RuntimeException(message)
class PremiumNotFoundException(message: String) : RuntimeException(message)
