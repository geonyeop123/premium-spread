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
            memberId = command.memberId,
            symbol = Symbol(command.symbol),
            koreaExchange = command.koreaExchange,
            koreaQuantity = command.koreaQuantity,
            koreaEntryPrice = command.koreaEntryPrice,
            foreignExchange = command.foreignExchange,
            foreignQuantity = command.foreignQuantity,
            foreignEntryPrice = command.foreignEntryPrice,
            foreignLeverage = command.foreignLeverage,
            entryFxRate = command.entryFxRate,
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

    @Transactional(readOnly = true)
    fun findAllOpenByMemberId(memberId: Long): List<Position> {
        return positionRepository.findAllOpenByMemberId(memberId)
    }

    @Transactional(readOnly = true)
    fun findAllClosedByMemberId(memberId: Long): List<Position> {
        return positionRepository.findAllClosedByMemberId(memberId)
    }
}
