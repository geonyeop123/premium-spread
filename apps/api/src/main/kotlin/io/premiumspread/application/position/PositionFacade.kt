package io.premiumspread.application.position

import io.premiumspread.application.common.ApplicationError
import io.premiumspread.application.common.ApplicationException
import io.premiumspread.domain.market.MarketPair
import io.premiumspread.domain.position.InvalidPositionException
import io.premiumspread.domain.position.Position
import io.premiumspread.domain.position.PositionPnl
import io.premiumspread.domain.position.PositionCommand
import io.premiumspread.domain.position.PositionService
import io.premiumspread.domain.premium.PremiumPolicy
import io.premiumspread.domain.premium.PremiumService
import io.premiumspread.domain.ticker.Symbol
import io.premiumspread.domain.ticker.Exchange
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
    fun openAutoPosition(criteria: PositionCriteria.OpenAuto): PositionResult.Detail = translateInvalidPosition {
        val pair = parsePair(criteria.symbol, criteria.koreaExchange, criteria.foreignExchange)
        val snapshot = premiumService.findLatestSnapshot(pair)
            ?: throw ApplicationException(ApplicationError.PREMIUM_SNAPSHOT_NOT_AVAILABLE)

        val age = Duration.between(snapshot.observedAt, clock.instant())
        if (age > SNAPSHOT_MAX_AGE) {
            throw ApplicationException(ApplicationError.STALE_PREMIUM_SNAPSHOT)
        }

        val command = PositionCommand.Create(
            memberId = criteria.memberId,
            symbol = criteria.symbol,
            koreaExchange = pair.koreaExchange,
            koreaQuantity = criteria.koreaQuantity,
            koreaEntryPrice = snapshot.koreaPrice,
            foreignExchange = pair.foreignExchange,
            foreignQuantity = criteria.foreignQuantity,
            foreignEntryPrice = snapshot.foreignPrice,
            foreignLeverage = criteria.foreignLeverage,
            entryFxRate = snapshot.fxRate,
            entryObservedAt = snapshot.observedAt,
        )
        val position = positionService.create(command)

        toDetail(position)
    }

    @Transactional
    fun openManualPosition(criteria: PositionCriteria.OpenManual): PositionResult.Detail = translateInvalidPosition {
        val pair = parsePair(criteria.symbol, criteria.koreaExchange, criteria.foreignExchange)
        val command = PositionCommand.Create(
            memberId = criteria.memberId,
            symbol = pair.symbol.code,
            koreaExchange = pair.koreaExchange,
            koreaQuantity = criteria.koreaQuantity,
            koreaEntryPrice = criteria.koreaEntryPrice,
            foreignExchange = pair.foreignExchange,
            foreignQuantity = criteria.foreignQuantity,
            foreignEntryPrice = criteria.foreignEntryPrice,
            foreignLeverage = criteria.foreignLeverage,
            entryFxRate = criteria.entryFxRate,
            entryObservedAt = criteria.entryObservedAt,
        )
        val position = positionService.create(command)

        toDetail(position)
    }

    @Transactional(readOnly = true)
    fun findById(criteria: PositionCriteria.FindById): PositionResult.Detail {
        val position = positionService.findById(criteria.positionId)
            ?: throw ApplicationException(ApplicationError.POSITION_NOT_FOUND)
        verifyOwnership(position, criteria.memberId)
        return toDetail(position)
    }

    @Transactional(readOnly = true)
    fun findAllOpenByMemberId(criteria: PositionCriteria.FindAllOpen): PositionResult.Details = PositionResult.Details(
            positionService.findAllOpenByMemberId(criteria.memberId).map(::toDetail),
        )

    @Transactional(readOnly = true)
    fun findAllClosedByMemberId(criteria: PositionCriteria.FindAllClosed): PositionResult.Details = PositionResult.Details(
            positionService.findAllClosedByMemberId(criteria.memberId).map(::toDetail),
        )

    @Transactional(readOnly = true)
    fun calculatePnl(criteria: PositionCriteria.CalculatePnl): PositionResult.Pnl = translateInvalidPosition {
        val position = positionService.findById(criteria.positionId)
            ?: throw ApplicationException(ApplicationError.POSITION_NOT_FOUND)
        verifyOwnership(position, criteria.memberId)

        val snapshot = premiumService.findLatestSnapshot(position.pair)
            ?: throw ApplicationException(ApplicationError.PREMIUM_NOT_FOUND)

        val pnl = position.calculatePnl(
            currentKoreaPrice = snapshot.koreaPrice,
            currentForeignPrice = snapshot.foreignPrice,
            currentFxRate = snapshot.fxRate,
            currentPremiumRate = PremiumPolicy.normalizeEntity(snapshot.premiumRate),
            calculatedAt = clock.instant(),
        )
        toPnl(criteria.positionId, pnl)
    }

    @Transactional(readOnly = true)
    fun getSummary(criteria: PositionCriteria.Summary): PositionResult.Summary {
        val openCount = positionService.countOpenByMemberId(criteria.memberId)
        val closedCount = positionService.countClosedByMemberId(criteria.memberId)
        return PositionResult.Summary(
            totalPositions = Math.toIntExact(openCount + closedCount),
            openPositions = Math.toIntExact(openCount),
            closedPositions = Math.toIntExact(closedCount),
        )
    }

    @Transactional
    fun closePosition(criteria: PositionCriteria.Close): PositionResult.Detail = translateInvalidPosition {
        val position = positionService.findById(criteria.positionId)
            ?: throw ApplicationException(ApplicationError.POSITION_NOT_FOUND)
        verifyOwnership(position, criteria.memberId)

        position.close()
        val savedPosition = positionService.save(position)

        toDetail(savedPosition)
    }

    private fun verifyOwnership(position: Position, memberId: Long) {
        if (position.memberId != memberId) {
            throw ApplicationException(ApplicationError.POSITION_NOT_FOUND)
        }
    }

    private fun toDetail(position: Position): PositionResult.Detail = PositionResult.Detail(
        id = position.id,
        memberId = position.memberId,
        symbol = position.symbol.code,
        koreaExchange = position.koreaExchange.name,
        koreaQuantity = position.koreaQuantity,
        koreaEntryPrice = position.koreaEntryPrice,
        foreignExchange = position.foreignExchange.name,
        foreignQuantity = position.foreignQuantity,
        foreignEntryPrice = position.foreignEntryPrice,
        foreignLeverage = position.foreignLeverage,
        entryFxRate = position.entryFxRate,
        entryPremiumRate = position.entryPremiumRate,
        entryObservedAt = position.entryObservedAt,
        status = position.status.name,
    )

    private fun toPnl(positionId: Long, pnl: PositionPnl): PositionResult.Pnl = PositionResult.Pnl(
        positionId = positionId,
        premiumDiff = pnl.premiumDiff,
        entryPremiumRate = pnl.entryPremiumRate,
        currentPremiumRate = pnl.currentPremiumRate,
        koreaPnl = pnl.koreaPnl,
        foreignPnlKrw = pnl.foreignPnlKrw,
        totalPnlKrw = pnl.totalPnlKrw,
        koreaCurrentValue = pnl.koreaCurrentValue,
        totalPnlPercent = pnl.totalPnlPercent,
        isProfit = pnl.isProfit(),
        calculatedAt = pnl.calculatedAt,
    )

    private inline fun <T> translateInvalidPosition(block: () -> T): T =
        try {
            block()
        } catch (ex: ApplicationException) {
            throw ex
        } catch (ex: InvalidPositionException) {
            throw ApplicationException(ApplicationError.INVALID_POSITION, ex)
        }

    private fun parsePair(symbol: String, koreaExchange: String, foreignExchange: String): MarketPair =
        try {
            MarketPair(
                symbol = Symbol(symbol),
                koreaExchange = Exchange.valueOf(koreaExchange),
                foreignExchange = Exchange.valueOf(foreignExchange),
            )
        } catch (ex: IllegalArgumentException) {
            throw ApplicationException(ApplicationError.INVALID_POSITION, ex)
        }
}
