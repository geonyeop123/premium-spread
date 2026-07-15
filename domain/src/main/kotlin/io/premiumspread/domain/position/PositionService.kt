package io.premiumspread.domain.position

import io.premiumspread.domain.market.MarketPair
import io.premiumspread.domain.ticker.Symbol
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class PositionService(private val positionRepository: PositionRepository) {

    @Transactional
    fun create(command: PositionCommand.Create): Position {
        val position = Position.create(
            PositionOpenSpec(
                memberId = command.memberId,
                pair = MarketPair(Symbol(command.symbol), command.koreaExchange, command.foreignExchange),
                koreaQuantity = command.koreaQuantity,
                koreaEntryPrice = command.koreaEntryPrice,
                foreignQuantity = command.foreignQuantity,
                foreignEntryPrice = command.foreignEntryPrice,
                foreignLeverage = command.foreignLeverage,
                entryFxRate = command.entryFxRate,
                entryObservedAt = command.entryObservedAt,
            ),
        )
        return positionRepository.save(position)
    }

    @Transactional
    fun save(position: Position): Position = positionRepository.save(position)

    @Transactional(readOnly = true)
    fun findById(id: Long): Position? = positionRepository.findById(id)

    @Transactional(readOnly = true)
    fun findAllOpen(): List<Position> = positionRepository.findAllOpen()

    @Transactional(readOnly = true)
    fun findAllOpenByMemberId(memberId: Long): List<Position> = positionRepository.findAllOpenByMemberId(memberId)

    @Transactional(readOnly = true)
    fun findAllClosedByMemberId(memberId: Long): List<Position> = positionRepository.findAllClosedByMemberId(memberId)

    @Transactional(readOnly = true)
    fun countOpenByMemberId(memberId: Long): Long = positionRepository.countOpenByMemberId(memberId)

    @Transactional(readOnly = true)
    fun countClosedByMemberId(memberId: Long): Long = positionRepository.countClosedByMemberId(memberId)
}
