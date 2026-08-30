package io.premiumspread.domain.tradeprep

import io.premiumspread.domain.market.MarketPair
import io.premiumspread.domain.ticker.Exchange
import io.premiumspread.domain.ticker.Symbol
import java.math.BigDecimal
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
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

    @Test
    fun `생성 시 잔고 스냅샷 id가 비어 있거나 공백뿐이면 거절된다`() {
        assertThatThrownBy { TradePreparation.create(spec(boundSnapshotId = "")) }
            .isInstanceOf(InvalidTradePreparationException::class.java)
        assertThatThrownBy { TradePreparation.create(spec(boundSnapshotId = "   ")) }
            .isInstanceOf(InvalidTradePreparationException::class.java)
    }

    @Test
    fun `registerTarget으로 재바인딩하는 잔고 스냅샷 id가 비어 있거나 공백뿐이면 거절된다`() {
        val draft = TradePreparation.create(spec(boundSnapshotId = "snap-1"))

        assertThatThrownBy {
            draft.registerTarget(
                desiredEntryPremiumRate = BigDecimal("3.00"),
                boundBalanceSnapshotId = "",
                boundBalanceBasis = BalanceBasis.FRESH,
                at = observedAt,
            )
        }.isInstanceOf(InvalidTradePreparationException::class.java)

        assertThatThrownBy {
            draft.registerTarget(
                desiredEntryPremiumRate = BigDecimal("3.00"),
                boundBalanceSnapshotId = "   ",
                boundBalanceBasis = BalanceBasis.FRESH,
                at = observedAt,
            )
        }.isInstanceOf(InvalidTradePreparationException::class.java)

        assertThat(draft.status).isEqualTo(TradePreparationStatus.DRAFT)
    }

    @Test
    fun `registerTarget은 DRAFT에서만 받아들이며 isRegisterable이 그 술어다`() {
        // T5 Facade는 기존 WATCHING을 무효화하기 전에 isRegisterable로 사전 조건을 검사한다.
        // 술어가 여기 하나뿐이므로 이 테스트가 양쪽 경로의 회귀를 함께 막는다 (D21).
        val draft = TradePreparation.create(spec("snap-1"))
        assertThat(draft.isRegisterable).isTrue()

        val watching = watchingPlan("snap-1")
        assertThat(watching.isRegisterable).isFalse()
        assertThatThrownBy {
            watching.registerTarget(
                desiredEntryPremiumRate = BigDecimal("3.00"),
                boundBalanceSnapshotId = "snap-2",
                boundBalanceBasis = BalanceBasis.FRESH,
                at = observedAt,
            )
        }.isInstanceOf(InvalidTradePreparationException::class.java)
        // 거절은 부분 변경을 남기지 않는다 — 결속도 목표도 그대로다.
        assertThat(watching.boundBalanceSnapshotId).isEqualTo("snap-1")
        assertThat(watching.status).isEqualTo(TradePreparationStatus.WATCHING)

        val invalidated = watchingPlan("snap-1").apply { invalidateOnOwnerRefresh(observedAt) }
        assertThat(invalidated.isRegisterable).isFalse()
        assertThatThrownBy {
            invalidated.registerTarget(
                desiredEntryPremiumRate = BigDecimal("3.00"),
                boundBalanceSnapshotId = "snap-2",
                boundBalanceBasis = BalanceBasis.FRESH,
                at = observedAt,
            )
        }.isInstanceOf(InvalidTradePreparationException::class.java)
        assertThat(invalidated.status).isEqualTo(TradePreparationStatus.INVALIDATED)
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
