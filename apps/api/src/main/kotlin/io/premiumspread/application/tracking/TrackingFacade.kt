package io.premiumspread.application.tracking

import io.premiumspread.application.common.ApplicationError
import io.premiumspread.application.common.ApplicationException
import io.premiumspread.domain.market.MarketPair
import io.premiumspread.domain.tracking.InvalidTrackingException
import io.premiumspread.domain.tracking.Tracking
import io.premiumspread.domain.premium.PremiumSnapshot
import io.premiumspread.domain.tracking.TrackingCloseSnapshot
import io.premiumspread.domain.tracking.TrackingGrossPnl
import io.premiumspread.domain.tracking.TrackingStatus
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

private const val PRICE_BASIS_CURRENT = "CURRENT_MARKET"
private const val PRICE_BASIS_STALE = "STALE_MARKET"
private const val PRICE_BASIS_ARCHIVED = "ARCHIVED_SNAPSHOT"

@Service
class TrackingFacade(
    private val trackingService: TrackingService,
    private val premiumService: PremiumService,
    private val clock: Clock,
) {

    companion object {
        private const val SNAPSHOT_MAX_AGE_SECONDS = 60L
        private val SNAPSHOT_MAX_AGE: Duration = Duration.ofSeconds(SNAPSHOT_MAX_AGE_SECONDS)

        /**
         * 환율 한도는 수집 주기에서 유도한다. batch 의 exchange-rate fixed-rate 가 30m 이므로
         * 그보다 작으면 정상 운영에서도 모든 archive 가 확정 불가가 된다 (design.md §5.3.2).
         */
        private val FX_MAX_AGE: Duration = Duration.ofMinutes(35)
    }

    /**
     * 신선도는 **양방향 유계**다. `age > max` 만 보면 생산자 clock skew 로 미래가 된 observedAt 이
     * 음수 age 를 만들어 통과한다 (design.md §5.3.2).
     */
    private fun inBounds(observedAt: Instant, now: Instant, max: Duration): Boolean {
        val age = Duration.between(observedAt, now)
        return !age.isNegative && age <= max
    }

    private fun isFresh(snapshot: PremiumSnapshot, now: Instant): Boolean =
        inBounds(snapshot.observedAt, now, SNAPSHOT_MAX_AGE) &&
            inBounds(snapshot.fxObservedAt, now, FX_MAX_AGE)

    @Transactional
    fun recordFromMarket(criteria: TrackingCriteria.RecordFromMarket): TrackingResult.Detail = translateInvalidTracking {
        val pair = parsePair(criteria.symbol, criteria.koreaExchange, criteria.foreignExchange)
        val snapshot = premiumService.findLatestSnapshot(pair)
            ?: throw ApplicationException(ApplicationError.PREMIUM_SNAPSHOT_NOT_AVAILABLE)

        if (!isFresh(snapshot, clock.instant())) {
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
            ?: throw ApplicationException(ApplicationError.TRACKING_NOT_FOUND)
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
    fun getGrossPnl(criteria: TrackingCriteria.GetGrossPnl): TrackingResult.GrossPnl = translateInvalidTracking {
        val tracking = trackingService.findById(criteria.trackingId)
            ?: throw ApplicationException(ApplicationError.TRACKING_NOT_FOUND)
        verifyOwnership(tracking, criteria.memberId)
        val now = clock.instant()

        when {
            tracking.status == TrackingStatus.ACTIVE -> {
                val snapshot = premiumService.findLatestSnapshot(tracking.pair)
                    ?: throw ApplicationException(ApplicationError.PREMIUM_NOT_FOUND)
                val basis = if (isFresh(snapshot, now)) PRICE_BASIS_CURRENT else PRICE_BASIS_STALE
                toGrossPnl(
                    criteria.trackingId,
                    basis,
                    tracking.grossPnl(
                        koreaPrice = snapshot.koreaPrice,
                        foreignPrice = snapshot.foreignPrice,
                        fxRate = snapshot.fxRate,
                        premiumRate = PremiumPolicy.normalizeEntity(snapshot.premiumRate),
                        observedAt = snapshot.observedAt,
                        fxObservedAt = snapshot.fxObservedAt,
                        calculatedAt = now,
                    ),
                )
            }

            tracking.hasConfirmedClose ->
                toGrossPnl(criteria.trackingId, PRICE_BASIS_ARCHIVED, tracking.confirmedGrossPnl(now))

            else -> throw ApplicationException(ApplicationError.TRACKING_CLOSE_SNAPSHOT_UNAVAILABLE)
        }
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

    /**
     * 추적을 종료한다. 시세를 확정하지 못해도 **거절하지 않는다** — 그 사실은 확정 손익 미제공으로
     * 드러나며, `409` 는 archive 가 아니라 gross-pnl 조회에서만 나온다 (design.md §5.3.2).
     */
    @Transactional
    fun archive(criteria: TrackingCriteria.Archive): TrackingResult.Detail = translateInvalidTracking {
        // 소유권과 soft-delete 를 잠금 술어에 함께 넣는다. 잠근 뒤 검사하면 남의 행을 잠글 수 있다.
        val tracking = trackingService.findOwnedByIdForUpdate(criteria.trackingId, criteria.memberId)
            ?: throw ApplicationException(ApplicationError.TRACKING_NOT_FOUND)

        val now = clock.instant()
        val snapshot = premiumService.findLatestSnapshot(tracking.pair)
            ?.takeIf { isFresh(it, now) }
            ?.let {
                TrackingCloseSnapshot(
                    koreaPrice = it.koreaPrice,
                    foreignPrice = it.foreignPrice,
                    fxRate = it.fxRate,
                    premiumRate = PremiumPolicy.normalizeEntity(it.premiumRate),
                    observedAt = it.observedAt,
                    fxObservedAt = it.fxObservedAt,
                )
            }

        tracking.archive(snapshot, now)
        toDetail(trackingService.save(tracking))
    }

    private fun verifyOwnership(tracking: Tracking, memberId: Long) {
        if (tracking.memberId != memberId) {
            throw ApplicationException(ApplicationError.TRACKING_NOT_FOUND)
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
        closedAt = tracking.closedAt,
        closePriceSource = tracking.closePriceSource?.name,
        hasConfirmedClose = tracking.hasConfirmedClose,
    )

    private fun toGrossPnl(trackingId: Long, priceBasis: String, pnl: TrackingGrossPnl): TrackingResult.GrossPnl =
        TrackingResult.GrossPnl(
            trackingId = trackingId,
            priceBasis = priceBasis,
            pnlBasis = TrackingResult.GrossPnl.PNL_BASIS,
            entryPremiumRate = pnl.entryPremiumRate,
            referencePremiumRate = pnl.referencePremiumRate,
            premiumRateDelta = pnl.premiumRateDelta,
            koreaLegGrossPnlKrw = pnl.koreaLegGrossPnlKrw,
            foreignLegGrossPnlKrw = pnl.foreignLegGrossPnlKrw,
            totalGrossPnlKrw = pnl.totalGrossPnlKrw,
            koreaLegNotionalKrw = pnl.koreaLegNotionalKrw,
            grossPnlPercentOfKoreaNotional = pnl.grossPnlPercentOfKoreaNotional,
            isGrossProfit = pnl.isGrossProfit,
            calculatedAt = pnl.calculatedAt,
            observedAt = pnl.observedAt,
            fxObservedAt = pnl.fxObservedAt,
        )

    private inline fun <T> translateInvalidTracking(block: () -> T): T =
        try {
            block()
        } catch (ex: ApplicationException) {
            throw ex
        } catch (ex: InvalidTrackingException) {
            throw ApplicationException(ApplicationError.INVALID_TRACKING, ex)
        }

    private fun parsePair(symbol: String, koreaExchange: String, foreignExchange: String): MarketPair =
        try {
            MarketPair(
                symbol = Symbol(symbol),
                koreaExchange = Exchange.valueOf(koreaExchange),
                foreignExchange = Exchange.valueOf(foreignExchange),
            )
        } catch (ex: IllegalArgumentException) {
            throw ApplicationException(ApplicationError.INVALID_TRACKING, ex)
        }
}
