package io.premiumspread.domain.tradeprep

import io.premiumspread.domain.market.MarketPair
import io.premiumspread.domain.ticker.Exchange
import io.premiumspread.domain.ticker.Symbol
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * AC6 — 우리 체결·owner refresh·reconcile 불일치 각각이 계획을 무효화한다. 시간 경과만으로는
 * 무효화하지 않는다 (design.md D4). `INVALIDATED`는 종점이며 `ARMED`는 무기한이다(D11·D15).
 */
class TradePreparationInvalidationTest {

    private val observedAt = Instant.parse("2026-08-30T00:00:00Z")

    @Test
    fun `체결 사건이 WATCHING 계획을 무효화한다`() {
        val plan = watchingPlan()

        val invalidated = plan.invalidateOnTrackingEvent(observedAt)

        assertThat(invalidated).isTrue
        assertThat(plan.status).isEqualTo(TradePreparationStatus.INVALIDATED)
        assertThat(plan.invalidationReason).isEqualTo(TradePreparationInvalidationReason.TRACKING_EVENT)
        assertThat(plan.invalidatedAt).isEqualTo(observedAt)
        assertThat(plan.version).isEqualTo(2L)
    }

    @Test
    fun `owner 명시 refresh가 WATCHING 계획을 무효화한다`() {
        val plan = watchingPlan()

        val invalidated = plan.invalidateOnOwnerRefresh(observedAt)

        assertThat(invalidated).isTrue
        assertThat(plan.status).isEqualTo(TradePreparationStatus.INVALIDATED)
        assertThat(plan.invalidationReason).isEqualTo(TradePreparationInvalidationReason.OWNER_REFRESH)
        assertThat(plan.version).isEqualTo(2L)
    }

    @Test
    fun `owner 명시 refresh가 ARMED 계획도 무효화한다`() {
        val plan = armedPlan()

        val invalidated = plan.invalidateOnOwnerRefresh(observedAt.plusSeconds(1))

        assertThat(invalidated).isTrue
        assertThat(plan.status).isEqualTo(TradePreparationStatus.INVALIDATED)
        assertThat(plan.invalidationReason).isEqualTo(TradePreparationInvalidationReason.OWNER_REFRESH)
    }

    @Test
    fun `reconcile 불일치가 WATCHING 계획을 무효화한다`() {
        val plan = watchingPlan()

        val invalidated = plan.invalidateOnReconcileMismatch("different-snapshot", observedAt)

        assertThat(invalidated).isTrue
        assertThat(plan.status).isEqualTo(TradePreparationStatus.INVALIDATED)
        assertThat(plan.invalidationReason).isEqualTo(TradePreparationInvalidationReason.RECONCILE_MISMATCH)
        assertThat(plan.version).isEqualTo(2L)
    }

    @Test
    fun `체결 사건이 ARMED 계획도 무효화한다`() {
        val plan = armedPlan()

        val invalidated = plan.invalidateOnTrackingEvent(observedAt)

        assertThat(invalidated).isTrue
        assertThat(plan.status).isEqualTo(TradePreparationStatus.INVALIDATED)
    }

    @Test
    fun `DRAFT 계획도 체결 사건으로 직접 무효화된다 — WATCHING을 거치지 않아도 된다`() {
        val plan = TradePreparation.create(spec())
        assertThat(plan.status).isEqualTo(TradePreparationStatus.DRAFT)
        assertThat(plan.version).isEqualTo(0L)

        val invalidated = plan.invalidateOnOwnerRefresh(observedAt)

        assertThat(invalidated).isTrue
        assertThat(plan.status).isEqualTo(TradePreparationStatus.INVALIDATED)
        assertThat(plan.invalidationReason).isEqualTo(TradePreparationInvalidationReason.OWNER_REFRESH)
        assertThat(plan.version).isEqualTo(1L)
    }

    @Test
    fun `시간이 아무리 지나도 무효화 사건 없이는 WATCHING 상태가 그대로다 — 시계 기반 무효화 경로가 없다`() {
        val plan = watchingPlan()

        // 목표(3.00)보다 높은 값 — 진입 방향(현재가 목표 이하로 내려와야 충족)에서는 미충족이다.
        val farFuture = observedAt.plus(Duration.ofDays(3650))
        val outcome = plan.evaluateCondition(currentPremiumRate = BigDecimal("10.00"), observedAt = farFuture)

        assertThat(outcome).isEqualTo(TradePreparationConditionOutcome.NOT_MET)
        assertThat(plan.status).isEqualTo(TradePreparationStatus.WATCHING)
        assertThat(plan.invalidationReason).isNull()
        assertThat(plan.version).isEqualTo(1L)
    }

    @Test
    fun `ARMED 상태에서는 evaluateCondition을 다시 호출할 수 없다 — 무기한이며 시계로 전이하지 않는다`() {
        val plan = armedPlan()

        assertThatThrownBy {
            plan.evaluateCondition(currentPremiumRate = BigDecimal("0.10"), observedAt = observedAt.plusSeconds(1))
        }.isInstanceOf(InvalidTradePreparationException::class.java)
        assertThat(plan.status).isEqualTo(TradePreparationStatus.ARMED)
    }

    @Test
    fun `INVALIDATED는 종점이다 — 다른 트리거로 재무효화를 시도해도 상태·사유·version이 바뀌지 않는다`() {
        val plan = watchingPlan()
        plan.invalidateOnTrackingEvent(observedAt)
        val versionAfterFirstInvalidation = plan.version

        val second = plan.invalidateOnOwnerRefresh(observedAt.plusSeconds(1))

        assertThat(second).isFalse
        assertThat(plan.status).isEqualTo(TradePreparationStatus.INVALIDATED)
        assertThat(plan.invalidationReason).isEqualTo(TradePreparationInvalidationReason.TRACKING_EVENT)
        assertThat(plan.invalidatedAt).isEqualTo(observedAt)
        assertThat(plan.version).isEqualTo(versionAfterFirstInvalidation)
    }

    @Test
    fun `프리미엄이 진입 목표와 정확히 같으면 조건 충족으로 판정해 ARMED로 전이한다 — 경계값은 포함된다`() {
        val plan = watchingPlan()

        val outcome = plan.evaluateCondition(currentPremiumRate = BigDecimal("3.00"), observedAt = observedAt)

        assertThat(outcome).isEqualTo(TradePreparationConditionOutcome.ARMED)
        assertThat(plan.status).isEqualTo(TradePreparationStatus.ARMED)
        assertThat(plan.conditionFirstMetAt).isEqualTo(observedAt)
        assertThat(plan.conditionFirstMetPremiumRate).isEqualByComparingTo(BigDecimal("3.00"))
        assertThat(plan.version).isEqualTo(2L)
    }

    @Test
    fun `프리미엄이 진입 목표보다 높으면 조건 미충족이다 — 진입은 목표 이하에서만 충족된다`() {
        val plan = watchingPlan()

        val outcome = plan.evaluateCondition(currentPremiumRate = BigDecimal("3.01"), observedAt = observedAt)

        assertThat(outcome).isEqualTo(TradePreparationConditionOutcome.NOT_MET)
        assertThat(plan.status).isEqualTo(TradePreparationStatus.WATCHING)
        assertThat(plan.conditionFirstMetAt).isNull()
    }

    @Test
    fun `등록 시점에 이미 목표보다 낮은 프리미엄이면 첫 평가에서 즉시 충족된다 — crossing을 요구하지 않는다`() {
        val plan = watchingPlan()

        val outcome = plan.evaluateCondition(currentPremiumRate = BigDecimal("0.80"), observedAt = observedAt)

        assertThat(outcome).isEqualTo(TradePreparationConditionOutcome.ARMED)
        assertThat(plan.status).isEqualTo(TradePreparationStatus.ARMED)
    }

    @Test
    fun `UNVERIFIED 결속 계획은 조건이 충족돼도 ARMED로 전이하지 않고 관측만 기록한다`() {
        val plan = watchingPlan(boundBalanceBasis = BalanceBasis.UNVERIFIED)

        val outcome = plan.evaluateCondition(currentPremiumRate = BigDecimal("1.00"), observedAt = observedAt)

        assertThat(outcome).isEqualTo(TradePreparationConditionOutcome.OBSERVED_ONLY)
        assertThat(plan.status).isEqualTo(TradePreparationStatus.WATCHING)
        assertThat(plan.conditionFirstMetAt).isEqualTo(observedAt)
        assertThat(plan.conditionFirstMetPremiumRate).isEqualByComparingTo(BigDecimal("1.00"))
    }

    @Test
    fun `INVALIDATED 계획은 조건이 충족돼도 ARMED로 되돌아가지 않는다`() {
        val plan = watchingPlan()
        plan.invalidateOnTrackingEvent(observedAt)

        assertThatThrownBy {
            plan.evaluateCondition(currentPremiumRate = BigDecimal("1.00"), observedAt = observedAt.plusSeconds(1))
        }.isInstanceOf(InvalidTradePreparationException::class.java)
        assertThat(plan.status).isEqualTo(TradePreparationStatus.INVALIDATED)
    }

    private fun watchingPlan(boundBalanceBasis: BalanceBasis = BalanceBasis.FRESH): TradePreparation {
        val plan = TradePreparation.create(spec())
        plan.registerTarget(
            desiredEntryPremiumRate = BigDecimal("3.00"),
            boundBalanceSnapshotId = "snap-1",
            boundBalanceBasis = boundBalanceBasis,
            at = observedAt,
        )
        return plan
    }

    private fun armedPlan(): TradePreparation {
        val plan = watchingPlan()
        // 목표(3.00)보다 낮은 값 — 진입 방향에서 충족.
        plan.evaluateCondition(currentPremiumRate = BigDecimal("2.00"), observedAt = observedAt)
        return plan
    }

    private fun spec() = TradePreparationSpec(
        ownerId = 1L,
        pair = MarketPair.default(Symbol("BTC")),
        boundBalanceSnapshotId = "snap-1",
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
