package io.premiumspread.domain.tradeprep

import io.premiumspread.domain.market.MarketPair
import io.premiumspread.domain.ticker.Exchange
import io.premiumspread.domain.ticker.Symbol
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

/**
 * reconcile 판정의 경계다 (design.md D5·D17, `dod.md` AC18). 여기서는 **대조 규칙**만 본다 —
 * scheduler → `JobExecutor` → 무효화 사슬은 `TradePreparationReconcileJobIntegrationTest` 가 진다.
 */
class TradePreparationReconcileServiceTest {

    @Test
    fun `결속 스냅샷과 현재 판정용 잔고가 다르면 WATCHING 계획을 무효화한다`() {
        val plan = activePlan(boundSnapshotId = "snap-1")
        val repository = FakeActivePlanRepository(plan)

        val summary = TradePreparationReconcileService(repository).reconcile(source(balance("snap-2")), NOW)

        assertThat(summary.outcome).isEqualTo(TradePreparationReconcileOutcome.RECONCILED)
        assertThat(summary.examined).isEqualTo(1)
        assertThat(summary.invalidated).isEqualTo(1)
        assertThat(plan.status).isEqualTo(TradePreparationStatus.INVALIDATED)
        assertThat(plan.invalidationReason).isEqualTo(TradePreparationInvalidationReason.RECONCILE_MISMATCH)
        assertThat(plan.invalidatedAt).isEqualTo(NOW)
        assertThat(repository.saved).containsExactly(plan)
    }

    @Test
    fun `결속 스냅샷과 현재 판정용 잔고가 같으면 상태를 바꾸지 않고 저장하지도 않는다`() {
        val plan = activePlan(boundSnapshotId = "snap-1")
        val repository = FakeActivePlanRepository(plan)

        val summary = TradePreparationReconcileService(repository).reconcile(source(balance("snap-1")), NOW)

        assertThat(summary.outcome).isEqualTo(TradePreparationReconcileOutcome.RECONCILED)
        assertThat(summary.examined).isEqualTo(1)
        assertThat(summary.invalidated).isZero()
        assertThat(plan.status).isEqualTo(TradePreparationStatus.WATCHING)
        assertThat(plan.invalidationReason).isNull()
        assertThat(repository.saved).isEmpty()
    }

    /**
     * 대조하지 못한 것과 불일치를 발견한 것은 다른 사실이다. 판정용 잔고가 없으면 계획을
     * **읽지도 않는다** — 조회조차 하지 않았음을 단언해 "읽고 나서 아무것도 안 했다"와 구분한다.
     */
    @Test
    fun `판정용 잔고가 없으면 계획을 조회하지도 무효화하지도 않는다`() {
        val plan = activePlan(boundSnapshotId = "snap-1")
        val repository = FakeActivePlanRepository(plan)

        val summary = TradePreparationReconcileService(repository).reconcile(source(null), NOW)

        assertThat(summary.outcome).isEqualTo(TradePreparationReconcileOutcome.BALANCE_UNAVAILABLE)
        assertThat(summary.examined).isZero()
        assertThat(summary.invalidated).isZero()
        assertThat(repository.activeQueries).isZero()
        assertThat(plan.status).isEqualTo(TradePreparationStatus.WATCHING)
    }

    /** 한 사이클이 활성 계획을 **전부** 다룬다 — 하나만 심으면 "첫 계획만 처리한다"도 통과한다. */
    @Test
    fun `한 사이클이 불일치 계획만 골라 전부 무효화한다`() {
        val matched = activePlan(boundSnapshotId = "snap-1")
        val mismatchedWatching = activePlan(boundSnapshotId = "snap-old")
        val mismatchedArmed = activePlan(boundSnapshotId = "snap-older", arm = true)
        val repository = FakeActivePlanRepository(matched, mismatchedWatching, mismatchedArmed)

        val summary = TradePreparationReconcileService(repository).reconcile(source(balance("snap-1")), NOW)

        assertThat(summary.examined).isEqualTo(3)
        assertThat(summary.invalidated).isEqualTo(2)
        assertThat(matched.status).isEqualTo(TradePreparationStatus.WATCHING)
        assertThat(mismatchedWatching.status).isEqualTo(TradePreparationStatus.INVALIDATED)
        assertThat(mismatchedArmed.status).isEqualTo(TradePreparationStatus.INVALIDATED)
        assertThat(repository.saved).containsExactly(mismatchedWatching, mismatchedArmed)
    }

    /**
     * 판정용 잔고를 이 메서드 **안에서**, 계획을 읽기 전에 읽는다.
     *
     * 잔고를 값으로 받으면 호출자(`TradePreparationReconcileJob`)가 `@Transactional` 프록시 밖에서
     * 읽게 된다. 그러면 `잔고 S1 읽기 → registerTarget 이 S2 결속 계획을 커밋 → 이 트랜잭션이
     * 그 계획을 보고 S1 과 달라 무효화` 순서가 성립해, 방금 등록한 멀쩡한 계획이 죽는다.
     * 시그니처가 port 인 것이 그 순서를 구조적으로 막고, 이 테스트가 읽기 순서를 고정한다.
     */
    @Test
    fun `판정용 잔고를 계획보다 먼저 이 호출 안에서 읽는다`() {
        val repository = FakeActivePlanRepository(activePlan(boundSnapshotId = "snap-1"))
        var activeQueriesWhenBalanceRead = -1
        val source = VerifiedBalanceReadPort {
            activeQueriesWhenBalanceRead = repository.activeQueries
            balance("snap-1")
        }

        val summary = TradePreparationReconcileService(repository).reconcile(source, NOW)

        assertThat(activeQueriesWhenBalanceRead).isZero()
        assertThat(repository.activeQueries).isEqualTo(1)
        assertThat(summary.outcome).isEqualTo(TradePreparationReconcileOutcome.RECONCILED)
    }

    /** 무효화 건수가 대조 건수를 넘는 조합은 만들어질 수 없다 — factory 가 유일한 생성 경로다. */
    @Test
    fun `reconciled 는 대조 건수를 넘는 무효화 건수를 거절한다`() {
        assertThatThrownBy { TradePreparationReconcileSummary.reconciled(examined = 1, invalidated = 2) }
            .isInstanceOf(IllegalArgumentException::class.java)

        assertThatThrownBy { TradePreparationReconcileSummary.reconciled(examined = -1, invalidated = 0) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    /**
     * `RECONCILED` 는 카운트를 잃어서 안 되고, `BALANCE_SOURCE_UNAVAILABLE` 은 빈 배선 여부라
     * Domain 이 관측할 수 없는 사실이다 — Job 만 그것을 안다. 막는 이유는 다르지만 둘 다
     * Domain 결과로 만들어져선 안 된다.
     */
    @Test
    fun `Domain 이 만들 수 없는 outcome 은 notReconciled 가 거절한다`() {
        assertThatThrownBy {
            TradePreparationReconcileSummary.notReconciled(TradePreparationReconcileOutcome.RECONCILED)
        }.isInstanceOf(IllegalArgumentException::class.java)

        assertThatThrownBy {
            TradePreparationReconcileSummary.notReconciled(TradePreparationReconcileOutcome.BALANCE_SOURCE_UNAVAILABLE)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    /**
     * 판정용 잔고를 한 번 돌려주는 port 다. 서비스가 값이 아니라 port 를 받는 이유는 잔고 읽기가
     * 트랜잭션 **안에서** 일어나야 하기 때문이고, 그 순서는 위 순서 계약 테스트가 고정한다.
     */
    private fun source(balance: VerifiedBalance?): VerifiedBalanceReadPort = VerifiedBalanceReadPort { balance }

    private fun balance(snapshotId: String): VerifiedBalance = VerifiedBalance.from(
        BalanceSnapshot(
            id = snapshotId,
            koreaBalance = BigDecimal("1000000"),
            foreignBalance = BigDecimal("700"),
            balanceBasis = BalanceBasis.FRESH,
            observedAt = NOW,
        ),
    )!!

    private fun activePlan(boundSnapshotId: String, arm: Boolean = false): TradePreparation {
        val plan = TradePreparation.create(
            TradePreparationSpec(
                ownerId = 1L,
                pair = PAIR,
                boundBalanceSnapshotId = boundSnapshotId,
                boundBalanceBasis = BalanceBasis.FRESH,
                referenceForeignPrice = BigDecimal("89500"),
                referenceFxRate = BigDecimal("1432.6"),
                referencePremiumRate = BigDecimal("2.00"),
                referenceObservedAt = NOW,
                referenceFxSource = Exchange.FX_PROVIDER,
                referenceFxObservedAt = NOW,
                quantity = BigDecimal("0.5"),
                leverage = BigDecimal("2.0"),
            ),
        )
        plan.registerTarget(
            desiredEntryPremiumRate = BigDecimal("1.50"),
            boundBalanceSnapshotId = boundSnapshotId,
            boundBalanceBasis = BalanceBasis.FRESH,
            at = NOW,
        )
        if (arm) plan.evaluateCondition(BigDecimal("1.00"), NOW)
        return plan
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-08-30T00:00:30Z")
        val PAIR: MarketPair = MarketPair(Symbol("BTC"), Exchange.BITHUMB, Exchange.BINANCE)
    }
}

/** 활성 계획 조회와 저장 호출을 그대로 드러내는 in-memory 저장소다. */
private class FakeActivePlanRepository(private vararg val active: TradePreparation) : TradePreparationRepository {

    val saved = mutableListOf<TradePreparation>()
    var activeQueries: Int = 0
        private set

    override fun save(plan: TradePreparation): TradePreparation {
        saved += plan
        return plan
    }

    override fun findById(id: Long): TradePreparation? = null

    override fun findActiveByOwnerId(ownerId: Long): TradePreparation? = null

    override fun findAllActive(): List<TradePreparation> {
        activeQueries++
        return active.toList()
    }

    override fun findAllWatchingByPair(pair: MarketPair): List<TradePreparation> =
        error("reconcile must not query the evaluation projection")
}
