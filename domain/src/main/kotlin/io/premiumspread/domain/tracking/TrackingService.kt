package io.premiumspread.domain.tracking

import io.premiumspread.domain.market.MarketPair
import io.premiumspread.domain.ticker.Symbol
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class TrackingService(private val trackingRepository: TrackingRepository) {

    @Transactional
    fun create(command: TrackingCommand.Create): Tracking {
        val tracking = Tracking.create(
            TrackingRecordSpec(
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
        return trackingRepository.save(tracking)
    }

    @Transactional
    fun save(tracking: Tracking): Tracking = trackingRepository.save(tracking)

    @Transactional(readOnly = true)
    fun findById(id: Long): Tracking? = trackingRepository.findById(id)

    @Transactional(readOnly = true)
    fun findAllOpen(): List<Tracking> = trackingRepository.findAllOpen()

    @Transactional(readOnly = true)
    fun findAllActiveByMemberId(memberId: Long): List<Tracking> = trackingRepository.findAllActiveByMemberId(memberId)

    @Transactional(readOnly = true)
    fun findAllArchivedByMemberId(memberId: Long): List<Tracking> = trackingRepository.findAllArchivedByMemberId(memberId)

    @Transactional(readOnly = true)
    fun countActiveByMemberId(memberId: Long): Long = trackingRepository.countActiveByMemberId(memberId)

    @Transactional(readOnly = true)
    fun countArchivedByMemberId(memberId: Long): Long = trackingRepository.countArchivedByMemberId(memberId)
}
