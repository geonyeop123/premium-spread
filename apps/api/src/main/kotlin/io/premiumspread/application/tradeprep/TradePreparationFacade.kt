package io.premiumspread.application.tradeprep

import io.premiumspread.application.common.ApplicationError
import io.premiumspread.application.common.ApplicationException
import io.premiumspread.domain.market.MarketPair
import io.premiumspread.domain.member.MemberService
import io.premiumspread.domain.premium.PremiumPolicy
import io.premiumspread.domain.premium.PremiumSnapshot
import io.premiumspread.domain.premium.PremiumService
import io.premiumspread.domain.ticker.Exchange
import io.premiumspread.domain.ticker.Symbol
import io.premiumspread.domain.tracking.Tracking
import io.premiumspread.domain.tracking.TrackingService
import io.premiumspread.domain.tradeprep.BalanceBasis
import io.premiumspread.domain.tradeprep.BalanceSnapshot
import io.premiumspread.domain.tradeprep.BalanceSnapshotReadPort
import io.premiumspread.domain.tradeprep.InvalidTradePreparationException
import io.premiumspread.domain.tradeprep.TradePrepPolicy
import io.premiumspread.domain.tradeprep.TradePrepSizing
import io.premiumspread.domain.tradeprep.TradePrepSizingCalculation
import io.premiumspread.domain.tradeprep.TradePreparation
import io.premiumspread.domain.tradeprep.TradePreparationService
import io.premiumspread.domain.tradeprep.TradePreparationSpec
import io.premiumspread.domain.tradeprep.TradePreparationStatus
import io.premiumspread.domain.tradeprep.VerifiedBalanceReadPort
import org.springframework.beans.factory.ObjectProvider
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Clock

/**
 * 거래 준비 owner 유스케이스다 (design.md D2~D23, `.ai/rules/architecture.md` Controller → Facade
 * → Domain).
 *
 * ## 이 Facade 가 소유하지 않는 것
 *
 * **상태 전이 로직을 중복 구현하지 않는다** (D21). 전이는 전부 [TradePreparation] 과
 * [TradePreparationService] 가 소유하고, 조건 평가(`WATCHING` → `ARMED`)는 `apps:batch` 의
 * 평가 Job 이 같은 Domain 경로로 수행한다 — 앱 모듈끼리는 서로를 참조할 수 없으므로 전이가 여기
 * 있으면 batch 가 쓸 길이 없다.
 *
 * ## production 이 도달하는 상태는 `WATCHING` 까지다 (D19·D20)
 *
 * [registerTarget] 은 exposure 를 만들지 않으므로 **판정용 호출이 아니다.** 결속 잔고의 검증
 * 수준은 가용한 원천이 정한다 — [verifiedBalanceReadPort] 빈이 있으면 판정용 fresh 읽기이고
 * `STALE` 이면 거절하며, declared 뿐이면 `UNVERIFIED` 결속으로 등록한다. fail-closed 경계는
 * `ARMED` 에 있다: `UNVERIFIED` 결속 계획은 조건이 충족돼도 상태가 바뀌지 않고 관측만 남는다.
 * production 에는 판정용 원천이 없으므로(D22) `ARMED` 에 도달하는 경로가 없고, **그것이 의도된
 * 종착점이다.** 우회로를 만들지 않는다 — D9 가 닫은 구멍이다.
 *
 * ## 잔고 원천
 *
 * 표시용·판정용 port 는 **선택적 주입**이다. 원천이 배선돼 있으면 그쪽을 읽고, 없으면 owner
 * 신고값([TradePreparationCriteria.Prepare])으로 `UNVERIFIED` 스냅샷을 만든다 —
 * D20 의 "검증 수준은 가용한 원천이 정한다"를 배선으로 그대로 표현한 것이다.
 *
 * `DeclaredBalanceAdapter` 를 여기서 만들지 않는 이유: 앱은 infrastructure 를 `runtimeOnly` 로만
 * 소비하므로 그 타입이 컴파일 경로에 없다(`.ai/rules/architecture.md`). 신고값 → `UNVERIFIED`
 * 규칙의 정의는 Domain 의 [BalanceSnapshot.declared] 한 곳이고 어댑터와 이 Facade 가 같은 것을
 * 쓴다.
 */
@Service
class TradePreparationFacade(
    private val tradePreparationService: TradePreparationService,
    private val trackingService: TrackingService,
    private val premiumService: PremiumService,
    private val memberService: MemberService,
    private val policy: TradePrepPolicy,
    private val balanceSnapshotReadPort: ObjectProvider<BalanceSnapshotReadPort>,
    private val verifiedBalanceReadPort: ObjectProvider<VerifiedBalanceReadPort>,
    private val clock: Clock,
) {

    /**
     * 잔고 조회 → 사이징 → lot/step 반올림·재판정 → 캡 판정 → `DRAFT` 계획 생성 (D2·D5·D12).
     *
     * 캡을 위반하거나 반올림 뒤 물량이 0 이면 계획을 만들지 않고 산출값과 위반 캡만 돌려준다
     * (§3, `TradePrepSizingCalculation.isPlannable`).
     *
     * D18 의 member 행 잠금은 여기 없다 — `DRAFT` 는 `active_key` 가 `NULL` 이라 tracking 과
     * 교차하는 불변식을 만들지 않는다. 잠금은 활성 계획을 만드는 [registerTarget] 이 진다.
     */
    @Transactional
    fun prepare(criteria: TradePreparationCriteria.Prepare): TradePreparationResult.Preparation = translateDomain {
        val pair = parsePair(criteria.symbol, criteria.koreaExchange, criteria.foreignExchange)

        // D13 1차 검사. 보유 중이면 준비금이 온전히 가용하지 않으므로 계산 자체가 성립하지 않는다.
        rejectWhenActiveTrackingExists(criteria.memberId)

        val balance = readDisplayBalance(criteria)
        val snapshot = premiumService.findLatestSnapshot(pair)
            ?: throw ApplicationException(ApplicationError.PREMIUM_SNAPSHOT_NOT_AVAILABLE)
        // D3: prepare 는 표시용이다. STALE 을 감추지도 거절하지도 않고 balanceBasis·observedAt 라벨로 드러낸다.
        val referencePremiumRate = PremiumPolicy.normalizeEntity(snapshot.premiumRate)

        val calculation = size(balance, snapshot.foreignPrice, snapshot.fxRate, referencePremiumRate)

        val plan = if (calculation.isPlannable) {
            tradePreparationService.create(
                TradePreparationSpec(
                    ownerId = criteria.memberId,
                    pair = pair,
                    boundBalanceSnapshotId = balance.id,
                    boundBalanceBasis = balance.balanceBasis,
                    referenceForeignPrice = snapshot.foreignPrice,
                    referenceFxRate = snapshot.fxRate,
                    referencePremiumRate = referencePremiumRate,
                    referenceObservedAt = snapshot.observedAt,
                    referenceFxSource = snapshot.fxSource,
                    referenceFxObservedAt = snapshot.fxObservedAt,
                    quantity = calculation.finalQuantity,
                    leverage = calculation.finalLeverage,
                ),
            )
        } else {
            null
        }

        toPreparation(
            plan = plan,
            pair = pair,
            balance = balance,
            calculation = calculation,
            snapshot = snapshot,
            referencePremiumRate = referencePremiumRate,
            previousTracking = findPreviousTracking(criteria.memberId, pair, referencePremiumRate),
        )
    }

    /**
     * 진입 목표 프리미엄을 등록해 `DRAFT` 를 `WATCHING` 으로 승격한다 (D6·D13·D18·D20·D23).
     *
     * 잠금 순서는 **member → plan** 이다 (D18) — 트랜잭션의 첫 문장이 owner 의 member 행
     * `SELECT … FOR UPDATE` 이고 계획 조회는 그 뒤다. `TrackingFacade` 의 생성·archive 경로도
     * 같은 순서로 같은 행을 잡으므로, 두 트랜잭션이 서로의 미커밋 상태를 못 보는 교차에서도
     * `ACTIVE` tracking 과 활성 계획이 공존 커밋되지 않는다.
     */
    @Transactional
    fun registerTarget(criteria: TradePreparationCriteria.RegisterTarget): TradePreparationResult.Detail =
        translateDomain {
            // ① member. 이보다 앞에 tracking·plan 조회를 두지 않는다 (D18 잠금 순서).
            lockOwner(criteria.memberId)

            // ② D13 재검사. prepare 통과 직후 tracking 이 생기는 교차를 여기서 잡는다. ①이 없으면
            //    이 검사와 동시의 tracking 생성이 서로를 못 보고 둘 다 커밋된다(write-skew).
            rejectWhenActiveTrackingExists(criteria.memberId)

            // ③ plan. owner-scoped 조회이므로 남의 계획은 존재를 노출하지 않는 404 다 (D10).
            val plan = findOwnedPlan(criteria.planId, criteria.memberId)

            replaceExistingActivePlan(criteria.memberId, plan)

            val (snapshotId, basis) = resolveBinding(plan)
            plan.registerTarget(
                desiredEntryPremiumRate = criteria.desiredEntryPremiumRate,
                boundBalanceSnapshotId = snapshotId,
                boundBalanceBasis = basis,
                at = clock.instant(),
            )
            toDetail(save(plan))
        }

    /** owner 의 명시 무효화 (D4·D11). 이미 `INVALIDATED` 면 아무 것도 하지 않는다 — 종점이다. */
    @Transactional
    fun invalidate(criteria: TradePreparationCriteria.Invalidate): TradePreparationResult.Detail =
        invalidateOwnedPlan(criteria.planId, criteria.memberId)

    /**
     * owner 의 명시 refresh (D4·D11). 결속 잔고를 더 이상 신뢰하지 않겠다는 owner 의 선언이므로
     * 계획을 무효화하고, owner 는 [prepare] 로 새 계획을 만든다.
     *
     * Domain 이 정의한 owner 발 무효화 사유는 `OWNER_REFRESH` 하나뿐이라(D4) [invalidate] 와 같은
     * 전이를 쓴다. 둘을 별도 유스케이스로 두는 것은 D11 이 "owner refresh 와 명시 무효화" 두
     * endpoint 를 요구하기 때문이고, 사유를 늘리려면 Domain enum 부터 바뀌어야 한다.
     */
    @Transactional
    fun refresh(criteria: TradePreparationCriteria.Refresh): TradePreparationResult.Detail =
        invalidateOwnedPlan(criteria.planId, criteria.memberId)

    /** owner-scoped 단건 조회 (D10). 남의 계획은 404 다. */
    @Transactional(readOnly = true)
    fun findById(criteria: TradePreparationCriteria.FindById): TradePreparationResult.Detail =
        toDetail(findOwnedPlan(criteria.planId, criteria.memberId))

    private fun invalidateOwnedPlan(planId: Long, memberId: Long): TradePreparationResult.Detail = translateDomain {
        val plan = findOwnedPlan(planId, memberId)
        if (plan.invalidateOnOwnerRefresh(clock.instant())) {
            save(plan)
        }
        toDetail(plan)
    }

    /**
     * 기존 활성 계획을 정리한다 (D11·D23).
     *
     * - `ARMED` — 거절한다. owner 가 확인을 앞둔 의도적 산출물이라 새 등록이 조용히 대체하면 안 된다
     * - `WATCHING` — 같은 트랜잭션에서 무효화하고 새 계획을 승격한다. `uk_trade_preparation_owner_active`
     *   가 두 활성 행을 동시에 허용하지 않으므로 승격 전에 슬롯을 비워야 한다
     */
    private fun replaceExistingActivePlan(memberId: Long, promoted: TradePreparation) {
        val active = tradePreparationService.findActiveByOwnerId(memberId) ?: return
        if (active.status == TradePreparationStatus.ARMED) {
            throw ApplicationException(ApplicationError.ARMED_PLAN_EXISTS)
        }
        if (active.id == promoted.id) return
        if (active.invalidateOnOwnerRefresh(clock.instant())) {
            save(active)
        }
    }

    /**
     * 결속할 잔고를 정한다 (D19·D20).
     *
     * | 원천 | 동작 |
     * |---|---|
     * | 판정용 port 가 배선돼 있다 | 매 호출 실제 조회. `STALE` 이거나 확보 실패면 거절 (D3) |
     * | declared 뿐이다 | `prepare` 가 결속한 `UNVERIFIED` 스냅샷을 유지한다 |
     *
     * declared 경로에서 새 신고값을 다시 받지 않는 이유: 그것은 "이름만 바꾼 신고값"으로 결속을
     * 갱신하는 경로이고, 검증 수준이 올라가지 않으면서 계획과 계산의 근거가 어긋난다.
     */
    private fun resolveBinding(plan: TradePreparation): Pair<String, BalanceBasis> {
        val port = verifiedBalanceReadPort.getIfAvailable()
            ?: return plan.boundBalanceSnapshotId to plan.boundBalanceBasis

        // 확보 실패(null)도 STALE 과 같은 fail-closed 다 — 판정용으로 쓸 수 없는 값이라는 점이 같다.
        val verified = port.findForDecision()
            ?: throw ApplicationException(ApplicationError.STALE_BALANCE_FOR_EXPOSURE)
        if (verified.balanceBasis == BalanceBasis.STALE) {
            throw ApplicationException(ApplicationError.STALE_BALANCE_FOR_EXPOSURE)
        }
        return verified.snapshotId to verified.balanceBasis
    }

    /**
     * 표시용 잔고를 읽는다 (D2). 원천이 배선돼 있으면 그쪽이 우선하고, 없으면 owner 신고값이다.
     * 어느 쪽이든 `balanceBasis` 가 신뢰 수준을 스스로 말한다.
     */
    private fun readDisplayBalance(criteria: TradePreparationCriteria.Prepare): BalanceSnapshot =
        balanceSnapshotReadPort.getIfAvailable()?.findLatest()
            ?: BalanceSnapshot.declared(
                koreaBalance = criteria.koreaBalance,
                foreignBalance = criteria.foreignBalance,
                observedAt = clock.instant(),
            )

    /**
     * 재진입 참조값이 되는 **가장 최근 종료된** 추적이다 (D8). 보유 중 포지션이 아니다.
     * 다른 `MarketPair` 의 진입 프리미엄으로 gap 을 만들지 않는다(`.ai/rules/architecture.md`).
     */
    private fun findPreviousTracking(
        memberId: Long,
        pair: MarketPair,
        currentPremiumRate: BigDecimal,
    ): TradePreparationResult.PreviousTracking? {
        val archived = trackingService.findAllArchivedByMemberId(memberId).filter { it.pair == pair }
        // closedAt 은 V15 이전에 종료된 행에서 null 이다. 그 경우 저장소의 최신 생성 순서를 따른다.
        val previous: Tracking? = archived.filter { it.closedAt != null }.maxByOrNull { it.closedAt!! }
            ?: archived.firstOrNull()
        return previous?.let { toPreviousTracking(it, currentPremiumRate) }
    }

    /**
     * 사이징 입력이 관계식의 정의역을 벗어나면(잔고 0·음수, 환율 0 등) [TradePrepSizing] 이
     * `IllegalArgumentException` 을 던진다. 이것만 422 로 옮긴다 — 호출자에게는 "이 입력으로는
     * 계획을 만들 수 없다"는 사실이다.
     *
     * `IllegalArgumentException` 을 유스케이스 전체에서 삼키지 않는 이유: `PremiumService` 의
     * snapshot pair 불변식도 같은 예외 타입이고, 그것이 안정된 Application error 로 둔갑하면
     * 배선 결함이 정상 거절처럼 보인다 (Phase 0 이 `TrackingFacade` 에서 내린 같은 판단).
     */
    private fun size(
        balance: BalanceSnapshot,
        foreignPrice: BigDecimal,
        fxRate: BigDecimal,
        premiumRatePercent: BigDecimal,
    ): TradePrepSizingCalculation = try {
        TradePrepSizing.size(
            koreaBalance = balance.koreaBalance,
            foreignBalance = balance.foreignBalance,
            fxRate = fxRate,
            foreignPrice = foreignPrice,
            premiumRatePercent = premiumRatePercent,
            policy = policy,
        )
    } catch (ex: IllegalArgumentException) {
        throw ApplicationException(ApplicationError.DOMAIN_ERROR, ex)
    }

    private fun rejectWhenActiveTrackingExists(memberId: Long) {
        if (trackingService.countActiveByMemberId(memberId) > 0) {
            throw ApplicationException(ApplicationError.ACTIVE_TRACKING_EXISTS)
        }
    }

    /**
     * owner 단위 직렬화 잠금 (D18). 행이 없으면 잠글 것도 없다 — owner 는 인증 principal 에서
     * 오고 `fk_trade_preparation_owner` 가 회원 없는 계획을 막으므로 정상 경로에서는 항상 있다.
     */
    private fun lockOwner(memberId: Long) {
        memberService.findByIdForUpdate(memberId)
    }

    private fun findOwnedPlan(planId: Long, memberId: Long): TradePreparation =
        tradePreparationService.findByIdAndOwnerId(planId, memberId)
            ?: throw ApplicationException(ApplicationError.TRADE_PREPARATION_NOT_FOUND)

    /**
     * 저장은 즉시 flush 된다(어댑터 계약). owner 당 활성 계획 유일성을 DB 가 막으면 —
     * 애플리케이션 규칙이 틀렸다는 뜻이므로(D16·D23 심층 방어) 안정된 409 로 변환한다.
     */
    private fun save(plan: TradePreparation): TradePreparation = try {
        tradePreparationService.save(plan)
    } catch (ex: DataIntegrityViolationException) {
        throw ApplicationException(ApplicationError.WATCHING_ALREADY_EXISTS, ex)
    }

    // ── Domain → Result 매핑 ────────────────────────────────────────────────
    // Application 계약의 public 시그니처가 Domain 타입을 노출하지 않도록 전부 private 이다
    // (architectureTest 의 facade contract 규칙, `TrackingFacade.toDetail` 과 같은 형태).

    @Suppress("LongParameterList")
    private fun toPreparation(
        plan: TradePreparation?,
        pair: MarketPair,
        balance: BalanceSnapshot,
        calculation: TradePrepSizingCalculation,
        snapshot: PremiumSnapshot,
        referencePremiumRate: BigDecimal,
        previousTracking: TradePreparationResult.PreviousTracking?,
    ): TradePreparationResult.Preparation = TradePreparationResult.Preparation(
        planId = plan?.id,
        status = plan?.status?.name,
        symbol = pair.symbol.code,
        koreaExchange = pair.koreaExchange.name,
        foreignExchange = pair.foreignExchange.name,
        balanceSnapshotId = balance.id,
        koreaBalance = balance.koreaBalance,
        foreignBalance = balance.foreignBalance,
        balanceBasis = balance.balanceBasis.name,
        balanceObservedAt = balance.observedAt,
        balanceRatio = calculation.balanceRatio,
        rawLeverage = calculation.rawLeverage,
        rawQuantity = calculation.rawQuantity,
        koreaRoundedQuantity = calculation.koreaRoundedQuantity,
        foreignRoundedQuantity = calculation.foreignRoundedQuantity,
        quantity = calculation.finalQuantity,
        leverage = calculation.finalLeverage,
        koreaShare = calculation.capVerdict.koreaShare,
        liquidationDistance = calculation.capVerdict.liquidationDistance,
        capViolations = calculation.capVerdict.violations.map { it.name }.sorted(),
        plannable = calculation.isPlannable,
        referenceForeignPrice = snapshot.foreignPrice,
        referenceFxRate = snapshot.fxRate,
        referencePremiumRate = referencePremiumRate,
        referenceObservedAt = snapshot.observedAt,
        referenceFxSource = snapshot.fxSource.name,
        referenceFxObservedAt = snapshot.fxObservedAt,
        previousTracking = previousTracking,
    )

    private fun toPreviousTracking(
        tracking: Tracking,
        currentPremiumRate: BigDecimal,
    ): TradePreparationResult.PreviousTracking = TradePreparationResult.PreviousTracking(
        trackingId = tracking.id,
        entryPremiumRate = tracking.entryPremiumRate,
        closedAt = tracking.closedAt,
        currentPremiumRate = currentPremiumRate,
        premiumRateGap = currentPremiumRate.subtract(tracking.entryPremiumRate),
    )

    private fun toDetail(plan: TradePreparation): TradePreparationResult.Detail = TradePreparationResult.Detail(
        id = plan.id,
        symbol = plan.symbol.code,
        koreaExchange = plan.koreaExchange.name,
        foreignExchange = plan.foreignExchange.name,
        status = plan.status.name,
        boundBalanceSnapshotId = plan.boundBalanceSnapshotId,
        boundBalanceBasis = plan.boundBalanceBasis.name,
        quantity = plan.quantity,
        leverage = plan.leverage,
        referenceForeignPrice = plan.referenceForeignPrice,
        referenceFxRate = plan.referenceFxRate,
        referencePremiumRate = plan.referencePremiumRate,
        referenceObservedAt = plan.referenceObservedAt,
        referenceFxSource = plan.referenceFxSource.name,
        referenceFxObservedAt = plan.referenceFxObservedAt,
        desiredEntryPremiumRate = plan.desiredEntryPremiumRate,
        conditionFirstMetAt = plan.conditionFirstMetAt,
        conditionFirstMetPremiumRate = plan.conditionFirstMetPremiumRate,
        invalidationReason = plan.invalidationReason?.name,
        invalidatedAt = plan.invalidatedAt,
        version = plan.version,
    )

    private fun parsePair(symbol: String, koreaExchange: String, foreignExchange: String): MarketPair =
        try {
            MarketPair(
                symbol = Symbol(symbol),
                koreaExchange = Exchange.valueOf(koreaExchange),
                foreignExchange = Exchange.valueOf(foreignExchange),
            )
        } catch (ex: IllegalArgumentException) {
            throw ApplicationException(ApplicationError.DOMAIN_ERROR, ex)
        }

    /** Domain 예외를 안정된 Application error 로 바꾼다. */
    private inline fun <T> translateDomain(block: () -> T): T =
        try {
            block()
        } catch (ex: ApplicationException) {
            throw ex
        } catch (ex: InvalidTradePreparationException) {
            throw ApplicationException(ApplicationError.DOMAIN_ERROR, ex)
        }
}
