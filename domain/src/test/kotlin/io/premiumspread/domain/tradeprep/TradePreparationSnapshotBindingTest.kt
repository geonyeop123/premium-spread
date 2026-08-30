package io.premiumspread.domain.tradeprep

import io.premiumspread.domain.market.MarketPair
import io.premiumspread.domain.ticker.Exchange
import io.premiumspread.domain.ticker.Symbol
import java.math.BigDecimal
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * AC5 — 판정용 잔고는 캐시에서 읽을 수 없고([VerifiedBalanceReadPort] 계약, T1), 계획은 잔고
 * 스냅샷 id에 결속되며 그 id가 바뀌면 무효다 (design.md D2·D5).
 */
class TradePreparationSnapshotBindingTest {

    private val observedAt = Instant.parse("2026-08-30T00:00:00Z")

    @Test
    fun `계획은 결속된 잔고 스냅샷 id와 balanceBasis를 보존한다`() {
        val plan = watchingPlan(boundSnapshotId = "snap-1")

        assertThat(plan.boundBalanceSnapshotId).isEqualTo("snap-1")
        assertThat(plan.boundBalanceBasis).isEqualTo(BalanceBasis.FRESH)
        assertThat(plan.version).isEqualTo(1L)
    }

    @Test
    fun `결속 스냅샷 id와 현재 판정용 잔고 id가 다르면 계획이 무효화된다`() {
        val plan = watchingPlan(boundSnapshotId = "snap-1")

        val invalidated = plan.invalidateOnReconcileMismatch("snap-2", observedAt)

        assertThat(invalidated).isTrue
        assertThat(plan.status).isEqualTo(TradePreparationStatus.INVALIDATED)
        assertThat(plan.invalidationReason).isEqualTo(TradePreparationInvalidationReason.RECONCILE_MISMATCH)
        assertThat(plan.invalidatedAt).isEqualTo(observedAt)
        assertThat(plan.version).isEqualTo(2L)
    }

    @Test
    fun `결속 스냅샷 id와 현재 판정용 잔고 id가 같으면 계획을 무효화하지 않는다`() {
        val plan = watchingPlan(boundSnapshotId = "snap-1")

        val invalidated = plan.invalidateOnReconcileMismatch("snap-1", observedAt)

        assertThat(invalidated).isFalse
        assertThat(plan.status).isEqualTo(TradePreparationStatus.WATCHING)
        assertThat(plan.invalidationReason).isNull()
        assertThat(plan.invalidatedAt).isNull()
        assertThat(plan.version).isEqualTo(1L)
    }

    @Test
    fun `ARMED 계획도 스냅샷 id 불일치로 무효화된다`() {
        val plan = watchingPlan(boundSnapshotId = "snap-1")
        plan.evaluateCondition(currentPremiumRate = BigDecimal("2.00"), observedAt = observedAt)
        assertThat(plan.status).isEqualTo(TradePreparationStatus.ARMED)

        val invalidated = plan.invalidateOnReconcileMismatch("snap-2", observedAt.plusSeconds(1))

        assertThat(invalidated).isTrue
        assertThat(plan.status).isEqualTo(TradePreparationStatus.INVALIDATED)
        assertThat(plan.invalidationReason).isEqualTo(TradePreparationInvalidationReason.RECONCILE_MISMATCH)
    }

    @Test
    fun `한 번 무효화된 계획은 스냅샷 id가 다시 바뀌어도 재무효화되지 않는다 — INVALIDATED는 종점이다`() {
        val plan = watchingPlan(boundSnapshotId = "snap-1")
        plan.invalidateOnReconcileMismatch("snap-2", observedAt)

        val second = plan.invalidateOnReconcileMismatch("snap-3", observedAt.plusSeconds(1))

        assertThat(second).isFalse
        assertThat(plan.status).isEqualTo(TradePreparationStatus.INVALIDATED)
        assertThat(plan.invalidationReason).isEqualTo(TradePreparationInvalidationReason.RECONCILE_MISMATCH)
        assertThat(plan.invalidatedAt).isEqualTo(observedAt)
        assertThat(plan.version).isEqualTo(2L)
    }

    private fun watchingPlan(boundSnapshotId: String): TradePreparation {
        val plan = TradePreparation.create(spec(boundSnapshotId))
        plan.registerTarget(
            desiredEntryPremiumRate = BigDecimal("3.00"),
            boundBalanceSnapshotId = boundSnapshotId,
            boundBalanceBasis = BalanceBasis.FRESH,
            at = observedAt,
        )
        return plan
    }

    private fun spec(boundSnapshotId: String) = TradePreparationSpec(
        ownerId = 1L,
        pair = MarketPair.default(Symbol("BTC")),
        boundBalanceSnapshotId = boundSnapshotId,
        boundBalanceBasis = BalanceBasis.FRESH,
        referenceForeignPrice = BigDecimal("90000"),
        referenceFxRate = BigDecimal("1400"),
        referencePremiumRate = BigDecimal("2.00"),
        referenceObservedAt = observedAt,
        referenceFxSource = Exchange.FX_PROVIDER,
        referenceFxObservedAt = observedAt,
        quantity = BigDecimal("0.5"),
        leverage = BigDecimal("2.0"),
    )
}
