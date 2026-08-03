package io.premiumspread.application.tracking

import io.premiumspread.application.common.ApplicationError
import io.premiumspread.application.common.ApplicationException
import io.premiumspread.domain.market.MarketPair
import io.premiumspread.domain.tracking.InvalidTrackingException
import io.premiumspread.domain.tracking.Tracking
import io.premiumspread.domain.tracking.TrackingGrossPnl
import io.premiumspread.domain.tracking.TrackingCommand
import io.premiumspread.domain.tracking.TrackingService
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
class TrackingFacade(
    private val trackingService: TrackingService,
    private val premiumService: PremiumService,
    private val clock: Clock,
) {

    companion object {
        private const val SNAPSHOT_MAX_AGE_SECONDS = 60L
        private val SNAPSHOT_MAX_AGE: Duration = Duration.ofSeconds(SNAPSHOT_MAX_AGE_SECONDS)
    }

    @Transactional
    fun recordFromMarket(criteria: TrackingCriteria.RecordFromMarket): TrackingResult.Detail = translateInvalidTracking {
        val pair = parsePair(criteria.symbol, criteria.koreaExchange, criteria.foreignExchange)
        val snapshot = premiumService.findLatestSnapshot(pair)
            ?: throw ApplicationException(ApplicationError.PREMIUM_SNAPSHOT_NOT_AVAILABLE)

        val age = Duration.between(snapshot.observedAt, clock.instant())
        if (age > SNAPSHOT_MAX_AGE) {
            throw ApplicationException(ApplicationError.STALE_PREMIUM_SNAPSHOT)
        }

        val command = TrackingCommand.Create(
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
        val tracking = trackingService.create(command)

        toDetail(tracking)
    }

    @Transactional
    fun record(criteria: TrackingCriteria.Record): TrackingResult.Detail = translateInvalidTracking {
        val pair = parsePair(criteria.symbol, criteria.koreaExchange, criteria.foreignExchange)
        val command = TrackingCommand.Create(
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
        val tracking = trackingService.create(command)

        toDetail(tracking)
    }

    @Transactional(readOnly = true)
    fun findById(criteria: TrackingCriteria.FindById): TrackingResult.Detail {
        val tracking = trackingService.findById(criteria.trackingId)
            ?: throw ApplicationException(ApplicationError.POSITION_NOT_FOUND)
        verifyOwnership(tracking, criteria.memberId)
        return toDetail(tracking)
    }

    @Transactional(readOnly = true)
    fun findAllActiveByMemberId(criteria: TrackingCriteria.FindAllActive): TrackingResult.Details = TrackingResult.Details(
            trackingService.findAllActiveByMemberId(criteria.memberId).map(::toDetail),
        )

    @Transactional(readOnly = true)
    fun findAllArchivedByMemberId(criteria: TrackingCriteria.FindAllArchived): TrackingResult.Details = TrackingResult.Details(
            trackingService.findAllArchivedByMemberId(criteria.memberId).map(::toDetail),
        )

    @Transactional(readOnly = true)
    fun calculatePnl(criteria: TrackingCriteria.CalculatePnl): TrackingResult.Pnl = translateInvalidTracking {
        val tracking = trackingService.findById(criteria.trackingId)
            ?: throw ApplicationException(ApplicationError.POSITION_NOT_FOUND)
        verifyOwnership(tracking, criteria.memberId)

        val snapshot = premiumService.findLatestSnapshot(tracking.pair)
            ?: throw ApplicationException(ApplicationError.PREMIUM_NOT_FOUND)

        val pnl = tracking.calculatePnl(
            currentKoreaPrice = snapshot.koreaPrice,
            currentForeignPrice = snapshot.foreignPrice,
            currentFxRate = snapshot.fxRate,
            currentPremiumRate = PremiumPolicy.normalizeEntity(snapshot.premiumRate),
            calculatedAt = clock.instant(),
        )
        toPnl(criteria.trackingId, pnl)
    }

    @Transactional(readOnly = true)
    fun getSummary(criteria: TrackingCriteria.Summary): TrackingResult.Summary {
        val openCount = trackingService.countActiveByMemberId(criteria.memberId)
        val closedCount = trackingService.countArchivedByMemberId(criteria.memberId)
        return TrackingResult.Summary(
            totalTrackings = Math.toIntExact(openCount + closedCount),
            activeTrackings = Math.toIntExact(openCount),
            archivedTrackings = Math.toIntExact(closedCount),
        )
    }

    @Transactional
    fun archive(criteria: TrackingCriteria.Archive): TrackingResult.Detail = translateInvalidTracking {
        val tracking = trackingService.findById(criteria.trackingId)
            ?: throw ApplicationException(ApplicationError.POSITION_NOT_FOUND)
        verifyOwnership(tracking, criteria.memberId)

        tracking.close()
        val savedTracking = trackingService.save(tracking)

        toDetail(savedTracking)
    }

    private fun verifyOwnership(tracking: Tracking, memberId: Long) {
        if (tracking.memberId != memberId) {
            throw ApplicationException(ApplicationError.POSITION_NOT_FOUND)
        }
    }

    private fun toDetail(tracking: Tracking): TrackingResult.Detail = TrackingResult.Detail(
        id = tracking.id,
        memberId = tracking.memberId,
        symbol = tracking.symbol.code,
        koreaExchange = tracking.koreaExchange.name,
        koreaQuantity = tracking.koreaQuantity,
        koreaEntryPrice = tracking.koreaEntryPrice,
        foreignExchange = tracking.foreignExchange.name,
        foreignQuantity = tracking.foreignQuantity,
        foreignEntryPrice = tracking.foreignEntryPrice,
        foreignLeverage = tracking.foreignLeverage,
        entryFxRate = tracking.entryFxRate,
        entryPremiumRate = tracking.entryPremiumRate,
        entryObservedAt = tracking.entryObservedAt,
        status = tracking.status.name,
    )

    private fun toPnl(trackingId: Long, pnl: TrackingGrossPnl): TrackingResult.Pnl = TrackingResult.Pnl(
        trackingId = trackingId,
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

    private inline fun <T> translateInvalidTracking(block: () -> T): T =
        try {
            block()
        } catch (ex: ApplicationException) {
            throw ex
        } catch (ex: InvalidTrackingException) {
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
