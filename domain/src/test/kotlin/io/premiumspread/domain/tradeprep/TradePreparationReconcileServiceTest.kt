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

        val summary = TradePreparationReconcileService(repository).reconcile(balance("snap-2"), NOW)

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

        val summary = TradePreparationReconcileService(repository).reconcile(balance("snap-1"), NOW)

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

        val summary = TradePreparationReconcileService(repository).reconcile(null, NOW)

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

        val summary = TradePreparationReconcileService(repository).reconcile(balance("snap-1"), NOW)

        assertThat(summary.examined).isEqualTo(3)
        assertThat(summary.invalidated).isEqualTo(2)
        assertThat(matched.status).isEqualTo(TradePreparationStatus.WATCHING)
        assertThat(mismatchedWatching.status).isEqualTo(TradePreparationStatus.INVALIDATED)
        assertThat(mismatchedArmed.status).isEqualTo(TradePreparationStatus.INVALIDATED)
        assertThat(repository.saved).containsExactly(mismatchedWatching, mismatchedArmed)
    }

    @Test
    fun `RECONCILED 는 notReconciled 로 만들 수 없다`() {
        assertThatThrownBy {
            TradePreparationReconcileSummary.notReconciled(TradePreparationReconcileOutcome.RECONCILED)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

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
