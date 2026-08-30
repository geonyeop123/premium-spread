package io.premiumspread.application.tradeprep

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import io.premiumspread.MemberFixtures
import io.premiumspread.TrackingFixtures
import io.premiumspread.application.common.ApplicationError
import io.premiumspread.application.common.ApplicationException
import io.premiumspread.domain.market.MarketPair
import io.premiumspread.domain.member.MemberService
import io.premiumspread.domain.premium.PremiumService
import io.premiumspread.domain.premium.PremiumSnapshot
import io.premiumspread.domain.ticker.Exchange
import io.premiumspread.domain.ticker.Symbol
import io.premiumspread.domain.tracking.TrackingService
import io.premiumspread.domain.tracking.TrackingStatus
import io.premiumspread.domain.tradeprep.BalanceBasis
import io.premiumspread.domain.tradeprep.BalanceSnapshot
import io.premiumspread.domain.tradeprep.BalanceSnapshotReadPort
import io.premiumspread.domain.tradeprep.TradePrepPolicy
import io.premiumspread.domain.tradeprep.TradePreparation
import io.premiumspread.domain.tradeprep.TradePreparationOwnerPolicy
import io.premiumspread.domain.tradeprep.TradePreparationService
import io.premiumspread.domain.tradeprep.TradePreparationSpec
import io.premiumspread.domain.tradeprep.TradePreparationStatus
import io.premiumspread.domain.tradeprep.VerifiedBalance
import io.premiumspread.domain.tradeprep.VerifiedBalanceReadPort
import io.premiumspread.withId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class TradePreparationFacadeTest {

    private lateinit var tradePreparationService: TradePreparationService
    private lateinit var trackingService: TrackingService
    private lateinit var premiumService: PremiumService
    private lateinit var memberService: MemberService
    private lateinit var balanceSnapshotProvider: ObjectProvider<BalanceSnapshotReadPort>
    private lateinit var verifiedBalanceProvider: ObjectProvider<VerifiedBalanceReadPort>
    private lateinit var facade: TradePreparationFacade

    private val now: Instant = Instant.parse("2026-08-30T03:00:00Z")
    private val pair = MarketPair(Symbol("BTC"), Exchange.BITHUMB, Exchange.BINANCE)
    private val memberId = 1L
    private val ownerEmail = "trade-prep-owner@example.com"

    /** ECO-5 §7 owner 결정값과 같은 형태. 값 자체는 설정이 소유한다 (design.md §3). */
    private val policy = TradePrepPolicy(
        leverageCap = BigDecimal("7"),
        efficiencyFloor = BigDecimal("0.60"),
        koreaLotSize = BigDecimal("0.0001"),
        foreignLotSize = BigDecimal("0.001"),
    )

    /** D10 허가 목록. [memberId] 의 회원 fixture 이메일 하나만 owner 다. */
    private val ownerPolicy = TradePreparationOwnerPolicy(setOf(ownerEmail))

    @BeforeEach
    fun setUp() {
        tradePreparationService = mockk()
        trackingService = mockk()
        premiumService = mockk()
        memberService = mockk()
        balanceSnapshotProvider = mockk()
        verifiedBalanceProvider = mockk()

        // production 배선에는 두 port 구현이 모두 없다 (D22, AC20). 그 상태를 기본값으로 둔다.
        every { balanceSnapshotProvider.getIfAvailable() } returns null
        every { verifiedBalanceProvider.getIfAvailable() } returns null
        every { memberService.findByIdForUpdate(any()) } returns
            MemberFixtures.activeMember(email = ownerEmail, id = memberId)
        every { memberService.findById(any()) } returns
            MemberFixtures.activeMember(email = ownerEmail, id = memberId)
        every { trackingService.countActiveByMemberId(any()) } returns 0L
        every { trackingService.findAllArchivedByMemberId(any()) } returns emptyList()

        facade = TradePreparationFacade(
            tradePreparationService,
            trackingService,
            premiumService,
            memberService,
            policy,
            ownerPolicy,
            balanceSnapshotProvider,
            verifiedBalanceProvider,
            Clock.fixed(now, ZoneOffset.UTC),
        )
    }

    // ── prepare ────────────────────────────────────────────────────────────

    @Test
    fun `prepare 는 신고 잔고를 UNVERIFIED 스냅샷으로 결속하고 산출값과 라벨을 함께 반환한다`() {
        every { premiumService.findLatestSnapshot(pair) } returns snapshot()
        val specSlot = mutableListOf<TradePreparationSpec>()
        every { tradePreparationService.create(capture(specSlot)) } answers { draft(specSlot.last()) }

        val result = facade.prepare(prepare())

        assertThat(result.plannable).isTrue()
        assertThat(result.planId).isEqualTo(10L)
        assertThat(result.status).isEqualTo("DRAFT")
        // D2·D3: 표시용 잔고의 신뢰 수준과 관측 시각이 응답에 실린다.
        assertThat(result.balanceBasis).isEqualTo("UNVERIFIED")
        assertThat(result.balanceObservedAt).isEqualTo(now)
        assertThat(result.balanceSnapshotId).startsWith("declared-")
        // D12: provenance 는 계획과 응답 양쪽에 그대로 보존된다.
        assertThat(result.referenceFxSource).isEqualTo("FX_PROVIDER")
        assertThat(result.referencePremiumRate).isEqualByComparingTo("3.00")
        assertThat(specSlot.single().boundBalanceBasis).isEqualTo(BalanceBasis.UNVERIFIED)
        assertThat(specSlot.single().ownerId).isEqualTo(memberId)
        assertThat(specSlot.single().pair).isEqualTo(pair)
    }

    @Test
    fun `prepare 는 lot size 로 내림한 물량과 그 물량으로 재계산한 레버리지를 계획에 넣는다`() {
        every { premiumService.findLatestSnapshot(pair) } returns snapshot()
        val specSlot = mutableListOf<TradePreparationSpec>()
        every { tradePreparationService.create(capture(specSlot)) } answers { draft(specSlot.last()) }

        val result = facade.prepare(prepare())

        // D12: 계획에 남는 것은 원시값이 아니라 반올림 뒤 채택 물량과 재계산 레버리지다.
        assertThat(result.quantity).isLessThan(result.rawQuantity)
        assertThat(result.leverage).isLessThanOrEqualTo(result.rawLeverage)
        assertThat(specSlot.single().quantity).isEqualByComparingTo(result.quantity)
        assertThat(specSlot.single().leverage).isEqualByComparingTo(result.leverage)
    }

    @Test
    fun `캡을 위반하면 계획을 만들지 않고 위반한 캡을 응답에 명시한다`() {
        // 빗썸 비중이 효율 하한(60%) 아래가 되도록 해외 잔고를 크게 잡는다.
        every { premiumService.findLatestSnapshot(pair) } returns snapshot()

        val result = facade.prepare(
            prepare(koreaBalance = BigDecimal("10000000"), foreignBalance = BigDecimal("100000")),
        )

        assertThat(result.plannable).isFalse()
        assertThat(result.planId).isNull()
        assertThat(result.status).isNull()
        assertThat(result.capViolations).contains("EFFICIENCY_CAP")
        verify(exactly = 0) { tradePreparationService.create(any()) }
    }

    @Test
    fun `prepare 는 직전 종료된 같은 pair 추적의 진입 프리미엄과 현재 gap 을 함께 준다`() {
        every { premiumService.findLatestSnapshot(pair) } returns snapshot()
        val specSlot = mutableListOf<TradePreparationSpec>()
        every { tradePreparationService.create(capture(specSlot)) } answers { draft(specSlot.last()) }
        every { trackingService.findAllArchivedByMemberId(memberId) } returns listOf(
            // 다른 pair 는 참조하지 않는다 — 다른 pair 의 프리미엄으로 gap 을 만들지 않는다.
            TrackingFixtures.openPosition(id = 7L, memberId = memberId, koreaExchange = Exchange.UPBIT),
            archivedTracking(id = 8L, closedAt = now.minusSeconds(600)),
            archivedTracking(id = 9L, closedAt = now.minusSeconds(60)),
        )

        val previous = facade.prepare(prepare()).previousTracking

        assertThat(previous).isNotNull
        assertThat(previous!!.trackingId).isEqualTo(9L)
        assertThat(previous.entryPremiumRate).isEqualByComparingTo("1.00")
        assertThat(previous.currentPremiumRate).isEqualByComparingTo("3.00")
        assertThat(previous.premiumRateGap).isEqualByComparingTo("2.00")
    }

    @Test
    fun `closedAt 이 없는 레거시 종료 행끼리는 id 가 큰 쪽을 직전 추적으로 본다`() {
        // 파생 쿼리에 ORDER BY 가 없어 저장소 반환 순서에 기댈 수 없다. 순서를 뒤집어 넣어도
        // 같은 행이 뽑혀야 D8("가장 최근에 종료된")이 성립한다.
        every { premiumService.findLatestSnapshot(pair) } returns snapshot()
        val specSlot = mutableListOf<TradePreparationSpec>()
        every { tradePreparationService.create(capture(specSlot)) } answers { draft(specSlot.last()) }
        every { trackingService.findAllArchivedByMemberId(memberId) } returns listOf(
            legacyArchivedTracking(id = 5L),
            legacyArchivedTracking(id = 12L),
            legacyArchivedTracking(id = 3L),
        )

        assertThat(facade.prepare(prepare()).previousTracking!!.trackingId).isEqualTo(12L)
    }

    @Test
    fun `허가된 owner 가 아니면 prepare 를 거절하고 아무 것도 읽지 않는다`() {
        // 가입만 한 다른 회원이다 — 인증은 통과했지만 D10 의 허가 목록에 없다.
        every { memberService.findById(memberId) } returns
            MemberFixtures.activeMember(email = "intruder@example.com", id = memberId)

        // 남의 계획 조회와 같은 오류다 (D10) — 403 은 이 기능의 존재를 알려준다.
        assertApplicationError(ApplicationError.TRADE_PREPARATION_NOT_FOUND) { facade.prepare(prepare()) }

        verify(exactly = 0) { tradePreparationService.create(any()) }
        // payload 검증·tracking·프리미엄 조회보다 앞에서 끊긴다 — 거절 사유가 요청 내용에 따라
        // 달라지면 그 차이 자체가 정보다.
        verify(exactly = 0) { trackingService.countActiveByMemberId(any()) }
        verify(exactly = 0) { premiumService.findLatestSnapshot(any()) }
    }

    @Test
    fun `허가 목록이 비면 아무도 prepare 할 수 없다`() {
        // 설정을 빠뜨린 배포가 "가입한 누구나 생성 가능"으로 열리지 않는다는 계약이다.
        val closed = TradePreparationFacade(
            tradePreparationService,
            trackingService,
            premiumService,
            memberService,
            policy,
            TradePreparationOwnerPolicy(emptySet()),
            balanceSnapshotProvider,
            verifiedBalanceProvider,
            Clock.fixed(now, ZoneOffset.UTC),
        )

        assertApplicationError(ApplicationError.TRADE_PREPARATION_NOT_FOUND) { closed.prepare(prepare()) }

        verify(exactly = 0) { tradePreparationService.create(any()) }
    }

    @Test
    fun `탈퇴한 회원은 access token 이 남아 있어도 허가되지 않는다`() {
        // findById 는 deleted_at IS NULL 로 거른다 — 행이 없다는 것 자체가 거절 사유다.
        every { memberService.findById(memberId) } returns null

        assertApplicationError(ApplicationError.TRADE_PREPARATION_NOT_FOUND) { facade.prepare(prepare()) }

        verify(exactly = 0) { tradePreparationService.create(any()) }
    }

    @Test
    fun `허가된 owner 가 아니면 registerTarget 도 거절하고 계획을 건드리지 않는다`() {
        // owner 였던 회원이 허가 목록에서 빠지면 이미 만들어 둔 DRAFT 가 남는다. 그 계획을
        // WATCHING 으로 올리는 것은 exposure 를 늘리는 요청이므로 여기서도 막는다.
        every { memberService.findByIdForUpdate(memberId) } returns
            MemberFixtures.activeMember(email = "revoked@example.com", id = memberId)

        assertApplicationError(ApplicationError.TRADE_PREPARATION_NOT_FOUND) {
            facade.registerTarget(
                TradePreparationCriteria.RegisterTarget(
                    planId = 10L,
                    memberId = memberId,
                    desiredEntryPremiumRate = BigDecimal("1.50"),
                ),
            )
        }

        verify(exactly = 0) { tradePreparationService.findByIdAndOwnerId(any(), any()) }
        verify(exactly = 0) { tradePreparationService.save(any()) }
    }

    @Test
    fun `보유 ACTIVE tracking 이 있으면 prepare 를 거절하고 계획을 만들지 않는다`() {
        every { trackingService.countActiveByMemberId(memberId) } returns 1L

        assertApplicationError(ApplicationError.ACTIVE_TRACKING_EXISTS) { facade.prepare(prepare()) }

        verify(exactly = 0) { tradePreparationService.create(any()) }
        verify(exactly = 0) { premiumService.findLatestSnapshot(any()) }
    }

    @Test
    fun `프리미엄 스냅샷이 없으면 준비를 진행하지 않는다`() {
        every { premiumService.findLatestSnapshot(pair) } returns null

        assertApplicationError(ApplicationError.PREMIUM_SNAPSHOT_NOT_AVAILABLE) { facade.prepare(prepare()) }
    }

    @Test
    fun `잘못된 거래소와 사이징 불가 입력은 안정된 DOMAIN_ERROR 로 변환한다`() {
        assertApplicationError(ApplicationError.DOMAIN_ERROR) { facade.prepare(prepare(koreaExchange = "UNKNOWN")) }

        every { premiumService.findLatestSnapshot(pair) } returns snapshot()
        assertApplicationError(ApplicationError.DOMAIN_ERROR) {
            facade.prepare(prepare(foreignBalance = BigDecimal.ZERO))
        }
    }

    @Test
    fun `premium snapshot 불변식 위반은 안정된 오류로 숨기지 않는다`() {
        // 배선 결함(다른 pair 의 스냅샷)이 정상 거절처럼 보이면 안 된다 — Phase 0 이 TrackingFacade 에서
        // 내린 것과 같은 판단이다.
        every { premiumService.findLatestSnapshot(pair) } throws IllegalArgumentException("snapshot pair mismatch")

        assertThatThrownBy { facade.prepare(prepare()) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .isNotInstanceOf(ApplicationException::class.java)
    }

    @Test
    fun `표시용 원천이 배선되면 신고값 대신 그 스냅샷을 쓴다`() {
        val port = BalanceSnapshotReadPort {
            BalanceSnapshot(
                id = "recorded-1",
                koreaBalance = BigDecimal("10000000"),
                foreignBalance = BigDecimal("2000"),
                balanceBasis = BalanceBasis.STALE,
                observedAt = now.minusSeconds(900),
            )
        }
        every { balanceSnapshotProvider.getIfAvailable() } returns port
        every { premiumService.findLatestSnapshot(pair) } returns snapshot()
        val specSlot = mutableListOf<TradePreparationSpec>()
        every { tradePreparationService.create(capture(specSlot)) } answers { draft(specSlot.last()) }

        val result = facade.prepare(prepare())

        // D3: STALE 을 조회에서 감추지 않고 라벨만 붙인다 — 거절은 exposure 를 늘리는 요청의 몫이다.
        assertThat(result.balanceBasis).isEqualTo("STALE")
        assertThat(result.balanceSnapshotId).isEqualTo("recorded-1")
        assertThat(result.plannable).isTrue()
    }

    // ── registerTarget ─────────────────────────────────────────────────────

    @Test
    fun `registerTarget 은 member 를 tracking·plan 보다 먼저 잠근다`() {
        val plan = draft(spec())
        every { tradePreparationService.findByIdAndOwnerId(10L, memberId) } returns plan
        every { tradePreparationService.findActiveByOwnerId(memberId) } returns null
        every { tradePreparationService.save(plan) } returns plan

        facade.registerTarget(TradePreparationCriteria.RegisterTarget(10L, memberId, BigDecimal("1.50")))

        // D18: 잠금 순서는 member → tracking/plan 이다. 뒤집으면 archive 경로와 반대라 교착이 생긴다.
        verifyOrder {
            memberService.findByIdForUpdate(memberId)
            trackingService.countActiveByMemberId(memberId)
            tradePreparationService.findByIdAndOwnerId(10L, memberId)
        }
    }

    @Test
    fun `declared 원천뿐이면 UNVERIFIED 결속으로 WATCHING 이 된다`() {
        val plan = draft(spec())
        every { tradePreparationService.findByIdAndOwnerId(10L, memberId) } returns plan
        every { tradePreparationService.findActiveByOwnerId(memberId) } returns null
        every { tradePreparationService.save(plan) } returns plan

        val result = facade.registerTarget(
            TradePreparationCriteria.RegisterTarget(10L, memberId, BigDecimal("1.50")),
        )

        // D19·D20: 판정용 원천이 없으므로 prepare 가 결속한 UNVERIFIED 를 유지한다.
        assertThat(result.status).isEqualTo("WATCHING")
        assertThat(result.boundBalanceBasis).isEqualTo("UNVERIFIED")
        assertThat(result.desiredEntryPremiumRate).isEqualByComparingTo("1.50")
    }

    @Test
    fun `판정용 원천이 있으면 fresh 읽기로 재결속하고 STALE 이면 거절한다`() {
        val fresh = verified(BalanceBasis.FRESH, "exchange-fresh")
        every { verifiedBalanceProvider.getIfAvailable() } returns VerifiedBalanceReadPort { fresh }
        val plan = draft(spec())
        every { tradePreparationService.findByIdAndOwnerId(10L, memberId) } returns plan
        every { tradePreparationService.findActiveByOwnerId(memberId) } returns null
        every { tradePreparationService.save(plan) } returns plan

        val result = facade.registerTarget(
            TradePreparationCriteria.RegisterTarget(10L, memberId, BigDecimal("1.50")),
        )

        assertThat(result.boundBalanceBasis).isEqualTo("FRESH")
        assertThat(result.boundBalanceSnapshotId).isEqualTo("exchange-fresh")

        // D3: exposure 로 이어지는 결속에서 STALE 은 fail-closed 다.
        val stale = verified(BalanceBasis.STALE, "exchange-stale")
        every { verifiedBalanceProvider.getIfAvailable() } returns VerifiedBalanceReadPort { stale }
        val other = draft(spec(), id = 11L)
        every { tradePreparationService.findByIdAndOwnerId(11L, memberId) } returns other
        assertApplicationError(ApplicationError.STALE_BALANCE_FOR_EXPOSURE) {
            facade.registerTarget(TradePreparationCriteria.RegisterTarget(11L, memberId, BigDecimal("1.50")))
        }
        assertThat(other.status).isEqualTo(TradePreparationStatus.DRAFT)
    }

    @Test
    fun `판정용 원천이 값을 확보하지 못해도 fail-closed 다`() {
        every { verifiedBalanceProvider.getIfAvailable() } returns VerifiedBalanceReadPort { null }
        val plan = draft(spec())
        every { tradePreparationService.findByIdAndOwnerId(10L, memberId) } returns plan
        every { tradePreparationService.findActiveByOwnerId(memberId) } returns null

        assertApplicationError(ApplicationError.STALE_BALANCE_FOR_EXPOSURE) {
            facade.registerTarget(TradePreparationCriteria.RegisterTarget(10L, memberId, BigDecimal("1.50")))
        }
        assertThat(plan.status).isEqualTo(TradePreparationStatus.DRAFT)
    }

    @Test
    fun `판정용 원천이 없으면 표시용이 FRESH 를 줘도 UNVERIFIED 로 강등해 결속한다`() {
        // ARMED 게이트는 boundBalanceBasis != UNVERIFIED 다. prepare 가 결속하는 basis 는 표시용
        // 스냅샷에서 오고 D2 는 표시용의 캐시를 허용하므로, 그 basis 를 그대로 물려주면 캐시에서
        // 읽은 잔고로 ARMED 에 도달한다 — AC5 가 금지하는 경로다.
        val plan = draft(spec().copy(boundBalanceSnapshotId = "cached-1", boundBalanceBasis = BalanceBasis.FRESH))
        every { tradePreparationService.findByIdAndOwnerId(10L, memberId) } returns plan
        every { tradePreparationService.findActiveByOwnerId(memberId) } returns null
        every { tradePreparationService.save(plan) } returns plan

        val result = facade.registerTarget(
            TradePreparationCriteria.RegisterTarget(10L, memberId, BigDecimal("1.50")),
        )

        assertThat(result.boundBalanceBasis).isEqualTo("UNVERIFIED")
        // 스냅샷 id 는 보존한다 — D5 결속과 reconcile 대조 대상이다.
        assertThat(result.boundBalanceSnapshotId).isEqualTo("cached-1")
        // 그 결과 조건이 충족돼도 상태가 바뀌지 않는다 (D19).
        assertThat(plan.evaluateCondition(BigDecimal("1.00"), now).name).isEqualTo("OBSERVED_ONLY")
        assertThat(plan.status).isEqualTo(TradePreparationStatus.WATCHING)
    }

    @Test
    fun `표시용 STALE 로 prepare 한 계획은 등록 뒤에도 ARMED 에 도달하지 못한다`() {
        // 리뷰 프로브를 그대로 재현한다: 표시용 어댑터(ACT-2가 추가할 바로 그것)가 캐시된 STALE 을
        // 돌려주는 상황에서 prepare → registerTarget → evaluateCondition 을 끝까지 통과시킨다.
        every { balanceSnapshotProvider.getIfAvailable() } returns BalanceSnapshotReadPort {
            BalanceSnapshot(
                id = "cached-stale-1",
                koreaBalance = BigDecimal("100000000"),
                foreignBalance = BigDecimal("20000"),
                balanceBasis = BalanceBasis.STALE,
                observedAt = now.minusSeconds(900),
            )
        }
        every { premiumService.findLatestSnapshot(pair) } returns snapshot()
        val created = mutableListOf<TradePreparation>()
        every { tradePreparationService.create(any()) } answers {
            draft(firstArg()).also { created += it }
        }
        every { tradePreparationService.findActiveByOwnerId(memberId) } returns null
        every { tradePreparationService.save(any()) } answers { firstArg() }
        every { tradePreparationService.findByIdAndOwnerId(10L, memberId) } answers { created.single() }

        val prepared = facade.prepare(prepare())
        assertThat(prepared.balanceBasis).isEqualTo("STALE")

        val registered = facade.registerTarget(
            TradePreparationCriteria.RegisterTarget(prepared.planId!!, memberId, BigDecimal("1.50")),
        )
        assertThat(registered.boundBalanceBasis).isEqualTo("UNVERIFIED")
        assertThat(registered.status).isEqualTo("WATCHING")

        val outcome = created.single().evaluateCondition(BigDecimal("1.00"), now)
        assertThat(outcome.name).isEqualTo("OBSERVED_ONLY")
        assertThat(created.single().status).isEqualTo(TradePreparationStatus.WATCHING)
        assertThat(created.single().conditionFirstMetAt).isEqualTo(now)
    }

    /**
     * D18 의 fail-closed 는 그대로다 — 잠글 행이 없으면 계획을 읽지도 저장하지도 않는다. 바뀐 것은
     * code 뿐이고, 그 이유는 아래 [prepare 와 registerTarget 은 같은 조건에 같은 code 를 준다] 다.
     */
    @Test
    fun `잠글 회원 행이 없으면 registerTarget 을 거절한다`() {
        every { memberService.findByIdForUpdate(memberId) } returns null

        assertApplicationError(ApplicationError.TRADE_PREPARATION_NOT_FOUND) {
            facade.registerTarget(TradePreparationCriteria.RegisterTarget(10L, memberId, BigDecimal("1.50")))
        }
        verify(exactly = 0) { tradePreparationService.findByIdAndOwnerId(any(), any()) }
        verify(exactly = 0) { tradePreparationService.save(any()) }
    }

    /**
     * soft-delete 된 회원이 유효한 access token 을 들고 온 **같은 조건**에서 두 endpoint 가 같은
     * code 를 내야 한다. 예전에는 `prepare` 가 `TRADE_PREPARATION_NOT_FOUND`,
     * `registerTarget` 이 `MEMBER_NOT_FOUND` 였다 — 둘 다 404 라 status 는 같지만 code 로 두
     * endpoint 를 구분할 수 있었고, 그건 KDoc·런북이 주장하는 균일성과 어긋난다.
     */
    @Test
    fun `prepare 와 registerTarget 은 같은 조건에 같은 code 를 준다`() {
        every { memberService.findById(memberId) } returns null
        every { memberService.findByIdForUpdate(memberId) } returns null

        assertApplicationError(ApplicationError.TRADE_PREPARATION_NOT_FOUND) { facade.prepare(prepare()) }
        assertApplicationError(ApplicationError.TRADE_PREPARATION_NOT_FOUND) {
            facade.registerTarget(TradePreparationCriteria.RegisterTarget(10L, memberId, BigDecimal("1.50")))
        }
    }

    @Test
    fun `DRAFT 가 아닌 계획으로 등록을 시도하면 기존 활성 계획을 건드리기 전에 거절한다`() {
        val existing = watching(draft(spec(), id = 30L))
        val alreadyWatching = watching(draft(spec(), id = 10L))
        every { tradePreparationService.findByIdAndOwnerId(10L, memberId) } returns alreadyWatching
        every { tradePreparationService.findActiveByOwnerId(memberId) } returns existing

        assertApplicationError(ApplicationError.DOMAIN_ERROR) {
            facade.registerTarget(TradePreparationCriteria.RegisterTarget(10L, memberId, BigDecimal("1.50")))
        }

        // 파괴적 단계가 검증보다 앞서 있었다면 existing 이 이미 INVALIDATED 였다.
        assertThat(existing.status).isEqualTo(TradePreparationStatus.WATCHING)
        verify(exactly = 0) { tradePreparationService.save(any()) }
    }

    @Test
    fun `registerTarget 은 ACTIVE tracking 을 다시 검사한다 — prepare 직후의 교차를 잡는다`() {
        every { trackingService.countActiveByMemberId(memberId) } returns 1L

        assertApplicationError(ApplicationError.ACTIVE_TRACKING_EXISTS) {
            facade.registerTarget(TradePreparationCriteria.RegisterTarget(10L, memberId, BigDecimal("1.50")))
        }

        verify(exactly = 0) { tradePreparationService.findByIdAndOwnerId(any(), any()) }
        verify(exactly = 0) { tradePreparationService.save(any()) }
    }

    @Test
    fun `기존 WATCHING 은 같은 트랜잭션에서 무효화하고 새 계획을 승격한다`() {
        val existing = watching(draft(spec(), id = 30L))
        val plan = draft(spec())
        every { tradePreparationService.findByIdAndOwnerId(10L, memberId) } returns plan
        every { tradePreparationService.findActiveByOwnerId(memberId) } returns existing
        every { tradePreparationService.save(any()) } answers { firstArg() }

        val result = facade.registerTarget(
            TradePreparationCriteria.RegisterTarget(10L, memberId, BigDecimal("1.50")),
        )

        // D11·D23: 승격 전에 활성 슬롯을 비운다. 순서가 뒤집히면 unique index 가 정상 경로를 막는다.
        assertThat(existing.status).isEqualTo(TradePreparationStatus.INVALIDATED)
        assertThat(result.status).isEqualTo("WATCHING")
        verifyOrder {
            tradePreparationService.save(existing)
            tradePreparationService.save(plan)
        }
    }

    @Test
    fun `기존 ARMED 는 새 등록이 조용히 대체하지 않고 거절된다`() {
        val armed = armed()
        val plan = draft(spec())
        every { tradePreparationService.findByIdAndOwnerId(10L, memberId) } returns plan
        every { tradePreparationService.findActiveByOwnerId(memberId) } returns armed

        assertApplicationError(ApplicationError.ARMED_PLAN_EXISTS) {
            facade.registerTarget(TradePreparationCriteria.RegisterTarget(10L, memberId, BigDecimal("1.50")))
        }

        assertThat(armed.status).isEqualTo(TradePreparationStatus.ARMED)
        assertThat(plan.status).isEqualTo(TradePreparationStatus.DRAFT)
        verify(exactly = 0) { tradePreparationService.save(any()) }
    }

    // ── owner scope · 무효화 · 조회 ─────────────────────────────────────────

    @Test
    fun `남의 계획은 존재를 노출하지 않는 404 로 통일한다`() {
        every { tradePreparationService.findByIdAndOwnerId(any(), any()) } returns null

        assertApplicationError(ApplicationError.TRADE_PREPARATION_NOT_FOUND) {
            facade.findById(TradePreparationCriteria.FindById(10L, memberId))
        }
        assertApplicationError(ApplicationError.TRADE_PREPARATION_NOT_FOUND) {
            facade.invalidate(TradePreparationCriteria.Invalidate(10L, memberId))
        }
        assertApplicationError(ApplicationError.TRADE_PREPARATION_NOT_FOUND) {
            facade.refresh(TradePreparationCriteria.Refresh(10L, memberId))
        }
        assertApplicationError(ApplicationError.TRADE_PREPARATION_NOT_FOUND) {
            facade.registerTarget(TradePreparationCriteria.RegisterTarget(10L, memberId, BigDecimal("1.50")))
        }
        verify(exactly = 0) { tradePreparationService.save(any()) }
    }

    @Test
    fun `invalidate 와 refresh 는 OWNER_REFRESH 로 무효화하고 두 번째 호출은 아무 것도 바꾸지 않는다`() {
        val plan = watching(draft(spec()))
        every { tradePreparationService.findByIdAndOwnerId(10L, memberId) } returns plan
        every { tradePreparationService.save(plan) } returns plan

        val invalidated = facade.invalidate(TradePreparationCriteria.Invalidate(10L, memberId))

        assertThat(invalidated.status).isEqualTo("INVALIDATED")
        assertThat(invalidated.invalidationReason).isEqualTo("OWNER_REFRESH")
        assertThat(invalidated.invalidatedAt).isEqualTo(now)

        // INVALIDATED 는 종점이다 — 재호출이 저장을 다시 부르지 않는다.
        val versionAfterFirst = invalidated.version
        val again = facade.refresh(TradePreparationCriteria.Refresh(10L, memberId))
        assertThat(again.version).isEqualTo(versionAfterFirst)
        verify(exactly = 1) { tradePreparationService.save(plan) }
    }

    @Test
    fun `findById 는 owner-scoped 조회 결과를 그대로 노출한다`() {
        val plan = watching(draft(spec()))
        every { tradePreparationService.findByIdAndOwnerId(10L, memberId) } returns plan

        val detail = facade.findById(TradePreparationCriteria.FindById(10L, memberId))

        assertThat(detail.id).isEqualTo(10L)
        assertThat(detail.status).isEqualTo("WATCHING")
        assertThat(detail.boundBalanceBasis).isEqualTo("UNVERIFIED")
    }

    // ── fixtures ───────────────────────────────────────────────────────────

    private fun assertApplicationError(expected: ApplicationError, block: () -> Unit) {
        assertThatThrownBy { block() }
            .isInstanceOf(ApplicationException::class.java)
            .hasFieldOrPropertyWithValue("error", expected)
    }

    private fun prepare(
        koreaExchange: String = "BITHUMB",
        koreaBalance: BigDecimal = BigDecimal("100000000"),
        foreignBalance: BigDecimal = BigDecimal("20000"),
    ) = TradePreparationCriteria.Prepare(
        memberId = memberId,
        symbol = "BTC",
        koreaExchange = koreaExchange,
        foreignExchange = "BINANCE",
        koreaBalance = koreaBalance,
        foreignBalance = foreignBalance,
    )

    private fun snapshot(observedAt: Instant = now.minusSeconds(5)) = PremiumSnapshot(
        pair = pair,
        premiumRate = BigDecimal("3.00"),
        koreaPrice = BigDecimal("129555000"),
        foreignPrice = BigDecimal("89500"),
        foreignPriceInKrw = BigDecimal("125781000"),
        fxRate = BigDecimal("1405.4"),
        observedAt = observedAt,
        fxSource = Exchange.FX_PROVIDER,
        fxObservedAt = observedAt,
    )

    private fun spec() = TradePreparationSpec(
        ownerId = memberId,
        pair = pair,
        boundBalanceSnapshotId = "declared-fixture",
        boundBalanceBasis = BalanceBasis.UNVERIFIED,
        referenceForeignPrice = BigDecimal("89500"),
        referenceFxRate = BigDecimal("1405.4"),
        referencePremiumRate = BigDecimal("3.00"),
        referenceObservedAt = now.minusSeconds(5),
        referenceFxSource = Exchange.FX_PROVIDER,
        referenceFxObservedAt = now.minusSeconds(5),
        quantity = BigDecimal("0.5"),
        leverage = BigDecimal("3"),
    )

    private fun draft(spec: TradePreparationSpec, id: Long = 10L): TradePreparation =
        TradePreparation.create(spec).withId(id)

    private fun watching(plan: TradePreparation): TradePreparation = plan.apply {
        registerTarget(BigDecimal("1.50"), boundBalanceSnapshotId, boundBalanceBasis, now.minusSeconds(60))
    }

    private fun armed(): TradePreparation {
        val plan = TradePreparation.create(
            spec().copy(boundBalanceSnapshotId = "recorded-1", boundBalanceBasis = BalanceBasis.FRESH),
        ).withId(20L)
        plan.registerTarget(BigDecimal("1.50"), "recorded-1", BalanceBasis.FRESH, now.minusSeconds(60))
        plan.evaluateCondition(BigDecimal("1.00"), now.minusSeconds(30))
        return plan
    }

    private fun verified(basis: BalanceBasis, id: String): VerifiedBalance = VerifiedBalance.from(
        BalanceSnapshot(
            id = id,
            koreaBalance = BigDecimal("100000000"),
            foreignBalance = BigDecimal("20000"),
            balanceBasis = basis,
            observedAt = now.minusSeconds(30),
        ),
    )!!

    /** V15 이전에 종료돼 `closedAt` 이 없는 ARCHIVED 행. `archive` 를 거치지 않고 상태만 바꾼다. */
    private fun legacyArchivedTracking(id: Long) =
        TrackingFixtures.openPosition(
            id = id,
            memberId = memberId,
            koreaExchange = Exchange.BITHUMB,
            koreaEntryPrice = BigDecimal("101"),
            foreignEntryPrice = BigDecimal("1"),
            entryFxRate = BigDecimal("100"),
        ).apply { status = TrackingStatus.ARCHIVED }

    /** 진입 프리미엄이 정확히 1.00%가 되도록 가격을 고른다 — (101 - 1×100)/100 × 100 = 1.00. */
    private fun archivedTracking(id: Long, closedAt: Instant) =
        TrackingFixtures.openPosition(
            id = id,
            memberId = memberId,
            koreaExchange = Exchange.BITHUMB,
            koreaEntryPrice = BigDecimal("101"),
            foreignEntryPrice = BigDecimal("1"),
            entryFxRate = BigDecimal("100"),
        ).apply { archive(null, closedAt) }
}
