package io.premiumspread.application.tradeprep

import io.premiumspread.application.common.ApplicationError
import io.premiumspread.application.common.ApplicationException
import io.premiumspread.domain.market.MarketPair
import io.premiumspread.domain.member.Member
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
import io.premiumspread.domain.tradeprep.TradePreparationOwnerPolicy
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
 * ## 허가된 owner 만 계획을 만들고 활성화한다 (D10)
 *
 * 인증은 게이트가 아니다 — 회원 가입이 공개 endpoint 라 인증만 요구하면 아무나 가입해 계획을
 * 만들 수 있다. [prepare] 와 [registerTarget] 은 인증 principal 의 회원이 허가된 owner 인지
 * 먼저 확인하고, 아니면 `TRADE_PREPARATION_NOT_FOUND` 로 거절한다
 * ([rejectWhenNotAuthorizedOwner]).
 *
 * [invalidate]·[refresh]·[findById] 에는 이 검사를 걸지 않는다. 셋 다 owner-scoped 조회를 거치므로
 * 허가되지 않은 회원에게는 애초에 보이는 계획이 없고, exposure 를 늘리지도 않는다. 오히려
 * 허가 목록에서 빠진 회원이 **자기 계획을 정리할 수단**을 잃는 쪽이 해롭다.
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
    private val ownerPolicy: TradePreparationOwnerPolicy,
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
        // D10. 어떤 payload 검증보다 앞이다 — 허가되지 않은 회원에게는 요청 내용과 무관하게 같은
        // 404 만 보여야 이 endpoint 의 존재 자체가 드러나지 않는다.
        rejectWhenNotAuthorizedOwner(memberService.findById(criteria.memberId))

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
            //    D10 허가 검사는 잠근 그 행으로 한다 — 조회를 앞에 하나 더 두면 D18 이 정한 첫
            //    문장이 바뀌고, 얻는 것도 없다.
            rejectWhenNotAuthorizedOwner(lockOwner(criteria.memberId))

            // ② D13 재검사. prepare 통과 직후 tracking 이 생기는 교차를 여기서 잡는다. ①이 없으면
            //    이 검사와 동시의 tracking 생성이 서로를 못 보고 둘 다 커밋된다(write-skew).
            rejectWhenActiveTrackingExists(criteria.memberId)

            // ③ plan. owner-scoped 조회이므로 남의 계획은 존재를 노출하지 않는 404 다 (D10).
            val plan = findOwnedPlan(criteria.planId, criteria.memberId)

            // ④ 거절 사유를 **전부** 파괴적 단계보다 앞에 둔다. 아래 replaceExistingActivePlan 은
            //    기존 `WATCHING` 을 무효화하고 즉시 flush 하므로, 그 뒤에 거절이 나면 멀쩡한 활성
            //    계획이 지워진 뒤 롤백에만 기대게 된다. 술어는 Domain 의 isRegisterable 하나이고
            //    registerTarget 의 검사와 공유한다 — 여기서 따로 적으면 Domain 이 허용 상태를
            //    넓혔을 때 이 경로만 조용히 막혀 apps:batch 와 갈라진다 (D21).
            //    두 단계를 맞바꿔 registerTarget 을 먼저 부를 수는 없다 — 엔티티를 더럽힌 뒤
            //    findActiveByOwnerId 가 auto-flush 를 일으키면 기존 `WATCHING` 이 아직 active_key 를
            //    쥔 채로 승격 UPDATE 가 나가 유일성이 즉시 깨진다.
            if (!plan.isRegisterable) {
                throw ApplicationException(
                    ApplicationError.DOMAIN_ERROR,
                    InvalidTradePreparationException("registerTarget requires a registerable plan, was ${plan.status}"),
                )
            }
            val (snapshotId, basis) = resolveBinding(plan)

            // ⑤ 여기서부터 파괴적이다.
            replaceExistingActivePlan(criteria.memberId)

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
    private fun replaceExistingActivePlan(memberId: Long) {
        val active = tradePreparationService.findActiveByOwnerId(memberId) ?: return
        if (active.status == TradePreparationStatus.ARMED) {
            throw ApplicationException(ApplicationError.ARMED_PLAN_EXISTS)
        }
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
     * | 판정용 원천이 없다 | 스냅샷 id 는 유지하고 basis 를 `UNVERIFIED` 로 **강등**한다 |
     *
     * **강등이 이 단위의 신뢰 경계다.** `ARMED` 게이트는 `TradePreparation.evaluateCondition` 의
     * `boundBalanceBasis != UNVERIFIED` 이고, `prepare` 가 결속한 basis 는 **표시용** 스냅샷에서
     * 온다 — D2 가 캐시를 명시적으로 허용하는 계약이다. 그 basis 를 그대로 물려주면 표시용
     * 어댑터(`ACT-2` 가 추가할 바로 그것)가 `FRESH`/`STALE` 을 돌려주는 순간 캐시에서 읽은 잔고로
     * `ARMED` 에 도달한다 — AC5 가 금지하는 "캐시에서 읽은 판정용 잔고"다. 판정용 port 를 거치지
     * 않은 값은 검증 수준이 없으므로 D20 표 그대로 `UNVERIFIED` 로 등록한다.
     *
     * 스냅샷 id 는 보존한다 — D5 의 결속과 T8 reconcile 의 대조 대상이 그 id 이기 때문이다.
     *
     * declared 경로에서 새 신고값을 다시 받지 않는 이유: 그것은 "이름만 바꾼 신고값"으로 결속을
     * 갱신하는 경로이고, 검증 수준이 올라가지 않으면서 계획과 계산의 근거가 어긋난다.
     */
    private fun resolveBinding(plan: TradePreparation): Pair<String, BalanceBasis> {
        val port = verifiedBalanceReadPort.getIfAvailable()
            ?: return plan.boundBalanceSnapshotId to BalanceBasis.UNVERIFIED

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
        // closedAt 은 V15 이전에 종료된 행에서 null 이다. 그런 행끼리는 최대 id 를 가장 최근으로 본다 —
        // 저장소 조회 순서에 기대면 파생 쿼리의 정렬 없는 결과에서 임의 행이 뽑혀 D8("가장 최근에
        // 종료된")을 어긴다.
        val previous: Tracking? = archived.filter { it.closedAt != null }.maxByOrNull { it.closedAt!! }
            ?: archived.maxByOrNull { it.id }
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
     * owner 단위 직렬화 잠금 (D18). **행이 없으면 fail-closed 다.**
     *
     * 잠금 쿼리는 `deleted_at IS NULL` 로 거르므로, soft-delete 된 회원이 아직 유효한 access token 을
     * 들고 있으면 매칭 행이 없다. 결과를 버리면 그 요청만 잠금 없이 통과해 D18 이 아무 신호 없이
     * write-skew 로 퇴화한다 — 잠금이 걸리지 않았다는 사실 자체가 거절 사유다.
     */
    private fun lockOwner(memberId: Long): Member =
        memberService.findByIdForUpdate(memberId)
            ?: throw ApplicationException(ApplicationError.MEMBER_NOT_FOUND)

    /**
     * 허가된 owner 만 계획을 만들거나 활성화할 수 있다 (D10, `dod.md` AC12).
     *
     * V1 은 단일 owner 인데(§1.2) 회원 가입은 공개 endpoint 다. 인증만 게이트로 삼으면 아무나
     * 가입해 자동매매 준비 계획을 만들 수 있고, 상위 `P3-O12` 가 그것을 금지한다. 허가 목록은
     * [TradePreparationOwnerPolicy] 가 소유하며 비어 있으면 아무도 허가되지 않는다.
     *
     * **거절은 `TRADE_PREPARATION_NOT_FOUND`(404) 다.** 403 을 쓰면 "허가된 owner 가 따로 있는
     * 자동매매 기능이 여기 있다"를 알려준다 — 남의 계획 조회를 404 로 답하기로 한 D10 의
     * "존재를 노출하지 않는" 판단과 같은 이유이고, 그 덕에 허가되지 않은 회원에게는 거래 준비
     * endpoint 전체가 한결같이 404 다.
     *
     * [owner] 가 `null` 인 경우(soft-delete 된 회원이 아직 유효한 access token 을 들고 있는 경우)도
     * 허가되지 않는다 — [lockOwner] 의 fail-closed 판단과 같다.
     */
    private fun rejectWhenNotAuthorizedOwner(owner: Member?) {
        if (!ownerPolicy.isAuthorized(owner?.email)) {
            throw ApplicationException(ApplicationError.TRADE_PREPARATION_NOT_FOUND)
        }
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
