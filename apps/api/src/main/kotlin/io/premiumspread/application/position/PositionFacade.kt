package io.premiumspread.application.position

import io.premiumspread.domain.market.MarketPair
import io.premiumspread.domain.position.InvalidPositionException
import io.premiumspread.domain.position.Position
import io.premiumspread.domain.position.PositionCommand
import io.premiumspread.domain.position.PositionService
import io.premiumspread.domain.premium.PremiumPolicy
import io.premiumspread.domain.premium.PremiumService
import io.premiumspread.domain.ticker.Symbol
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant

@Service
class PositionFacade(
    private val positionService: PositionService,
    private val premiumService: PremiumService,
    private val clock: Clock,
) {

    companion object {
        private const val SNAPSHOT_MAX_AGE_SECONDS = 60L
        private val SNAPSHOT_MAX_AGE: Duration = Duration.ofSeconds(SNAPSHOT_MAX_AGE_SECONDS)
    }

    @Transactional
    fun openAutoPosition(criteria: PositionCriteria.OpenAuto): PositionResult.Detail {
        val pair = runCatching {
            MarketPair(
                symbol = Symbol(criteria.symbol),
                koreaExchange = criteria.koreaExchange,
                foreignExchange = criteria.foreignExchange,
            )
        }.getOrElse { throw InvalidPositionException(it.message ?: "Invalid market pair.") }
        val snapshot = premiumService.findLatestSnapshot(pair)
            ?: throw PremiumSnapshotNotAvailableException(
                "Premium snapshot not available for symbol: ${criteria.symbol}",
            )

        val age = Duration.between(snapshot.observedAt, clock.instant())
        if (age > SNAPSHOT_MAX_AGE) {
            throw StalePremiumSnapshotException(
                "Premium snapshot is stale (age=${age.toMillis()}ms, max=${SNAPSHOT_MAX_AGE.toMillis()}ms) for symbol: ${criteria.symbol}",
            )
        }

        val command = PositionCommand.Create(
            memberId = criteria.memberId,
            symbol = criteria.symbol,
            koreaExchange = criteria.koreaExchange,
            koreaQuantity = criteria.koreaQuantity,
            koreaEntryPrice = snapshot.koreaPrice,
            foreignExchange = criteria.foreignExchange,
            foreignQuantity = criteria.foreignQuantity,
            foreignEntryPrice = snapshot.foreignPrice,
            foreignLeverage = criteria.foreignLeverage,
            entryFxRate = snapshot.fxRate,
            entryObservedAt = snapshot.observedAt,
        )
        val position = positionService.create(command)

        return PositionResult.Detail.from(position)
    }

    @Transactional
    fun openManualPosition(criteria: PositionCriteria.OpenManual): PositionResult.Detail {
        val command = PositionCommand.Create(
            memberId = criteria.memberId,
            symbol = criteria.symbol,
            koreaExchange = criteria.koreaExchange,
            koreaQuantity = criteria.koreaQuantity,
            koreaEntryPrice = criteria.koreaEntryPrice,
            foreignExchange = criteria.foreignExchange,
            foreignQuantity = criteria.foreignQuantity,
            foreignEntryPrice = criteria.foreignEntryPrice,
            foreignLeverage = criteria.foreignLeverage,
            entryFxRate = criteria.entryFxRate,
            entryObservedAt = criteria.entryObservedAt,
        )
        val position = positionService.create(command)

        return PositionResult.Detail.from(position)
    }

    @Transactional(readOnly = true)
    fun findById(id: Long, memberId: Long): PositionResult.Detail? {
        val position = positionService.findById(id) ?: return null
        verifyOwnership(position, memberId)
        return PositionResult.Detail.from(position)
    }

    @Transactional(readOnly = true)
    fun findAllOpenByMemberId(memberId: Long): List<PositionResult.Detail> {
        return positionService.findAllOpenByMemberId(memberId)
            .map { PositionResult.Detail.from(it) }
    }

    @Transactional(readOnly = true)
    fun findAllClosedByMemberId(memberId: Long): List<PositionResult.Detail> {
        return positionService.findAllClosedByMemberId(memberId)
            .map { PositionResult.Detail.from(it) }
    }

    @Transactional(readOnly = true)
    fun calculatePnl(positionId: Long, memberId: Long): PositionResult.Pnl {
        val position = positionService.findById(positionId)
            ?: throw PositionNotFoundException("Position not found: $positionId")
        verifyOwnership(position, memberId)

        val snapshot = premiumService.findLatestSnapshot(position.pair)
            ?: throw PremiumNotFoundException("Premium not found for symbol: ${position.symbol.code}")

        val pnl = position.calculatePnl(
            currentKoreaPrice = snapshot.koreaPrice,
            currentForeignPrice = snapshot.foreignPrice,
            currentFxRate = snapshot.fxRate,
            currentPremiumRate = PremiumPolicy.normalizeEntity(snapshot.premiumRate),
            calculatedAt = clock.instant(),
        )
        return PositionResult.Pnl.from(positionId, pnl)
    }

    @Transactional(readOnly = true)
    fun getSummary(memberId: Long): PositionResult.Summary {
        val openCount = positionService.findAllOpenByMemberId(memberId).size
        val closedCount = positionService.findAllClosedByMemberId(memberId).size
        return PositionResult.Summary(
            totalPositions = openCount + closedCount,
            openPositions = openCount,
            closedPositions = closedCount,
        )
    }

    @Transactional
    fun closePosition(positionId: Long, memberId: Long): PositionResult.Detail {
        val position = positionService.findById(positionId)
            ?: throw PositionNotFoundException("Position not found: $positionId")
        verifyOwnership(position, memberId)

        position.close()
        val savedPosition = positionService.save(position)

        return PositionResult.Detail.from(savedPosition)
    }

    private fun verifyOwnership(position: Position, memberId: Long) {
        if (position.memberId != memberId) {
            throw PositionNotFoundException("Position not found: ${position.id}")
        }
    }
}

class PositionNotFoundException(message: String) : RuntimeException(message)
class PremiumNotFoundException(message: String) : RuntimeException(message)
class PremiumSnapshotNotAvailableException(message: String) : RuntimeException(message)
class StalePremiumSnapshotException(message: String) : RuntimeException(message)
