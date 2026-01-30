package io.premiumspread.domain.position

import io.premiumspread.domain.ticker.Symbol
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class PositionService(
    private val positionRepository: PositionRepository,
) {

    @Transactional
    fun create(command: PositionCommand.Create): Position {
        val position = Position.create(
            symbol = Symbol(command.symbol),
            exchange = command.exchange,
            quantity = command.quantity,
            entryPrice = command.entryPrice,
            entryFxRate = command.entryFxRate,
            entryPremiumRate = command.entryPremiumRate,
            entryObservedAt = command.entryObservedAt,
        )
        return positionRepository.save(position)
    }

    @Transactional
    fun save(position: Position): Position {
        return positionRepository.save(position)
    }

    @Transactional(readOnly = true)
    fun findById(id: Long): Position? {
        return positionRepository.findById(id)
    }

    @Transactional(readOnly = true)
    fun findAllOpen(): List<Position> {
        return positionRepository.findAllOpen()
    }
}
