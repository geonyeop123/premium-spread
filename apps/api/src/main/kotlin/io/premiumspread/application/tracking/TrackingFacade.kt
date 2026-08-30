package io.premiumspread.application.tracking

import io.premiumspread.application.common.ApplicationError
import io.premiumspread.application.common.ApplicationException
import io.premiumspread.domain.market.MarketPair
import io.premiumspread.domain.member.MemberService
import io.premiumspread.domain.tradeprep.TradePreparationService
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

/**
 * 추적 유스케이스다.
 *
 * ## 체결 무효화 producer (design.md D17)
 *
 * 생성·archive 경로가 **같은 DB 트랜잭션**에서 이 owner 의 활성 거래 준비 계획(`WATCHING`·
 * `ARMED`)을 무효화한다. 이벤트나 `@Async` listener 로 옮기지 않는다 — 프로젝트 규칙이 그것을
 * 전달 보장으로 쓰는 것을 금지하고(`.ai/rules/batch.md`), 기존 "활성 구독 조회와 enqueue 는 같은
 * transaction" 선례를 따른다. 전이 자체는 `TradePreparationService` 가 소유한다 (D21).
 *
 * ## owner 단위 직렬화 (design.md D18)
 *
 * 생성·archive 는 트랜잭션 시작점에서 owner 의 member 행을 `SELECT … FOR UPDATE` 로 잠근다.
 * **잠금 순서는 항상 member → tracking/plan 이다** — archive 가 이미 잡는 tracking 행 잠금보다
 * member 를 먼저 잡아 교착을 막는다. 이 잠금이 없으면 `TradePreparationFacade.registerTarget` 의
 * `ACTIVE` 재검사와 여기의 tracking 생성이 서로의 미커밋 상태를 못 본 채 둘 다 커밋되어
 * `ACTIVE` tracking 과 활성 계획이 공존한다(write-skew) — version 술어도 unique index 도 교차
 * 테이블 불변식은 지키지 못한다.
 */
@Service
class TrackingFacade(
    private val trackingService: TrackingService,
    private val premiumService: PremiumService,
    private val memberService: MemberService,
    private val tradePreparationService: TradePreparationService,
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
        // D18: 트랜잭션 시작점의 member 잠금. 이보다 앞에 tracking·plan 조회를 두지 않는다.
        lockOwner(criteria.memberId)

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
        invalidateActivePlanOnTrackingEvent(criteria.memberId)

        toDetail(tracking)
    }

    @Transactional
    fun record(criteria: TrackingCriteria.Record): TrackingResult.Detail = translateInvalidTracking {
        // D18: 트랜잭션 시작점의 member 잠금. 이보다 앞에 tracking·plan 조회를 두지 않는다.
        lockOwner(criteria.memberId)

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
        invalidateActivePlanOnTrackingEvent(criteria.memberId)

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
        // D18: member 를 tracking 보다 먼저 잠근다. 아래 주석이 말하는 "첫 조회"는 **Tracking 엔티티
        // 를 영속성 컨텍스트에 올리는 첫 조회**를 뜻하며, member 행 잠금은 다른 엔티티라 그 조건을
        // 깨지 않는다. 순서를 뒤집으면(tracking → member) 생성 경로(member → tracking)와 반대라
        // 교착이 생긴다.
        lockOwner(criteria.memberId)

        // 잠금이 이 트랜잭션의 **첫 조회**여야 한다. 앞에 findById 를 두면 엔티티가 영속성 컨텍스트에
        // 먼저 올라가고, 뒤따르는 FOR UPDATE 쿼리는 DB 잠금은 잡되 **1차 캐시의 낡은 인스턴스**를
        // 돌려준다. 그러면 status 검사가 stale ACTIVE 를 보고 동시 요청이 모두 통과한다.
        // 실측으로 확인했다 — 조회를 잠금 앞으로 옮겼더니 동시 6요청 중 4건이 성공했다.
        //
        // 그래서 시세 조회는 잠금 뒤에 둔다. findLatestSnapshot 이 Redis·DB 를 읽는 동안 행이 잠겨
        // 있다는 대가를 치르지만(codex 코드리뷰 medium-3), 정확히 한 번만 확정된다는 계약이 우선이다.
        // 이 대가를 없애려면 트랜잭션 경계를 둘로 쪼개야 하고 그것은 Phase 0 범위를 벗어난다.
        val tracking = trackingService.findOwnedByIdForUpdate(criteria.trackingId, criteria.memberId)
            ?: throw ApplicationException(ApplicationError.TRACKING_NOT_FOUND)

        val now = clock.instant()
        val snapshot = premiumService.findLatestSnapshot(tracking.pair)
            ?.takeIf { isFresh(it, now) }
            ?.let {
                // of 는 0·음수를 null 로 돌려준다. 캐시 파서가 그런 값을 통과시키므로
                // 확정 저장 직전에 막지 않으면 되돌릴 수 없는 잘못된 확정이 남는다.
                TrackingCloseSnapshot.of(
                    koreaPrice = it.koreaPrice,
                    foreignPrice = it.foreignPrice,
                    fxRate = it.fxRate,
                    premiumRate = PremiumPolicy.normalizeEntity(it.premiumRate),
                    observedAt = it.observedAt,
                    fxObservedAt = it.fxObservedAt,
                )
            }

        tracking.archive(snapshot, now)
        val archived = trackingService.save(tracking)
        invalidateActivePlanOnTrackingEvent(criteria.memberId)
        toDetail(archived)
    }

    /**
     * 체결 사건으로 이 owner 의 활성 계획을 무효화한다 (D17). 같은 트랜잭션이며 전이는
     * Domain 서비스가 소유한다 (D21). 활성 계획이 없으면 아무 일도 하지 않는다.
     */
    private fun invalidateActivePlanOnTrackingEvent(memberId: Long) {
        tradePreparationService.invalidateActiveOnTrackingEvent(memberId, clock.instant())
    }

    /**
     * owner 단위 직렬화 잠금 (D18). 행이 없으면 잠글 것도 없다 — memberId 는 인증 principal 에서
     * 오므로 정상 경로에서는 항상 존재한다.
     */
    private fun lockOwner(memberId: Long) {
        memberService.findByIdForUpdate(memberId)
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
