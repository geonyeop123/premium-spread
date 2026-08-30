package io.premiumspread.domain.tradeprep

import io.premiumspread.domain.market.MarketPair
import io.premiumspread.domain.ticker.Exchange
import io.premiumspread.domain.ticker.Symbol
import java.math.BigDecimal
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * AC13 — `DeclaredBalanceAdapter`의 `UNVERIFIED` 스냅샷으로는 [VerifiedBalance]를 만들 수 없다
 * (design.md D9). 이 불가능성은 [VerifiedBalance.from]이 강제하고, 생성자는 이 클래스 밖에서
 * 호출할 수 없다(컴파일러가 강제).
 */
class TradePreparationBalanceTrustTest {

    private val observedAt = Instant.parse("2026-08-30T00:00:00Z")

    @Test
    fun `UNVERIFIED 스냅샷으로는 VerifiedBalance를 만들 수 없다`() {
        val declared = snapshot(BalanceBasis.UNVERIFIED)

        assertThat(VerifiedBalance.from(declared)).isNull()
    }

    @Test
    fun `UNAVAILABLE 스냅샷으로도 VerifiedBalance를 만들 수 없다`() {
        val unavailable = snapshot(BalanceBasis.UNAVAILABLE)

        assertThat(VerifiedBalance.from(unavailable)).isNull()
    }

    @Test
    fun `FRESH 스냅샷은 VerifiedBalance로 변환되고 필드를 보존한다`() {
        val fresh = snapshot(BalanceBasis.FRESH)

        val verified = VerifiedBalance.from(fresh)

        assertThat(verified).isNotNull
        assertThat(verified!!.snapshotId).isEqualTo(fresh.id)
        assertThat(verified.koreaBalance).isEqualByComparingTo(fresh.koreaBalance)
        assertThat(verified.foreignBalance).isEqualByComparingTo(fresh.foreignBalance)
        assertThat(verified.balanceBasis).isEqualTo(BalanceBasis.FRESH)
        assertThat(verified.observedAt).isEqualTo(fresh.observedAt)
    }

    @Test
    fun `STALE 스냅샷도 VerifiedBalance로 변환된다 — 신선도 거절은 소비자(registerTarget) 책임이다`() {
        val stale = snapshot(BalanceBasis.STALE)

        val verified = VerifiedBalance.from(stale)

        assertThat(verified).isNotNull
        assertThat(verified!!.balanceBasis).isEqualTo(BalanceBasis.STALE)
    }

    @Test
    fun `FRESH·STALE 스냅샷 결속으로는 같은 조건에서 ARMED에 필요한 판정용 잔고에 도달한다`() {
        val freshBound = snapshot(BalanceBasis.FRESH, id = "verified-1")
        val staleBound = snapshot(BalanceBasis.STALE, id = "verified-2")

        assertThat(VerifiedBalance.from(freshBound)).isNotNull
        assertThat(VerifiedBalance.from(staleBound)).isNotNull
    }

    @Test
    fun `RecordedBalanceAdapter 결속으로는 같은 조건에서 ARMED에 도달한다`() {
        // declared(UNVERIFIED)는 구조적으로 VerifiedBalance를 만들 수 없다(위 테스트들) — 이 테스트는
        // 그 반대편, "검증 가능한 원천이면 실제로 ARMED까지 간다"를 RecordedBalanceAdapter(T4,
        // test source set 전용)로 end-to-end 증명한다.
        val recorded = RecordedBalanceAdapter(
            koreaBalance = BigDecimal("24000000"),
            foreignBalance = BigDecimal("3500"),
            observedAt = observedAt,
        )
        val verified = recorded.findForDecision()
        assertThat(verified).isNotNull

        val plan = TradePreparation.create(
            TradePreparationSpec(
                ownerId = 1L,
                pair = MarketPair(Symbol("BTC"), Exchange.BITHUMB, Exchange.BINANCE),
                boundBalanceSnapshotId = verified!!.snapshotId,
                boundBalanceBasis = verified.balanceBasis,
                referenceForeignPrice = BigDecimal("70000"),
                referenceFxRate = BigDecimal("1400"),
                referencePremiumRate = BigDecimal("3.50"),
                referenceObservedAt = observedAt,
                referenceFxSource = Exchange.FX_PROVIDER,
                referenceFxObservedAt = observedAt,
                quantity = BigDecimal("0.1"),
                leverage = BigDecimal("3"),
            ),
        )
        plan.registerTarget(
            desiredEntryPremiumRate = BigDecimal("3.00"),
            boundBalanceSnapshotId = verified.snapshotId,
            boundBalanceBasis = verified.balanceBasis,
            at = observedAt,
        )

        val outcome = plan.evaluateCondition(
            currentPremiumRate = BigDecimal("1.00"),
            observedAt = observedAt.plusSeconds(1),
        )

        assertThat(outcome).isEqualTo(TradePreparationConditionOutcome.ARMED)
        assertThat(plan.status).isEqualTo(TradePreparationStatus.ARMED)
    }

    @Test
    fun `무장 게이트는 검증 가능한 결속만 통과시킨다 — BalanceBasis 전체를 고정한다`() {
        // D9·D19 의 fail-closed 경계는 `UNVERIFIED 면 막는다`가 아니라 `FRESH·STALE 만 통과`다.
        // 상수 하나가 늘 때 게이트가 소리 없이 열리지 않는다는 것이 이 계약이고, 그래서 enum 의
        // 네 값을 전부 적는다 — 값이 늘면 이 테스트부터 컴파일되지 않는다.
        val armed = mapOf(
            BalanceBasis.FRESH to TradePreparationConditionOutcome.ARMED,
            BalanceBasis.STALE to TradePreparationConditionOutcome.ARMED,
            BalanceBasis.UNVERIFIED to TradePreparationConditionOutcome.OBSERVED_ONLY,
            // 원천 조회 실패다. VerifiedBalance 로 변환되지 않는 값이므로(위 테스트) 무장에도
            // 쓰이지 않는다 — 두 경계가 같은 답을 준다.
            BalanceBasis.UNAVAILABLE to TradePreparationConditionOutcome.OBSERVED_ONLY,
        )
        assertThat(armed.keys).containsExactlyInAnyOrder(*BalanceBasis.entries.toTypedArray())

        armed.forEach { (basis, expected) ->
            val plan = watchingPlan(basis)

            val outcome = plan.evaluateCondition(BigDecimal("1.00"), observedAt.plusSeconds(1))

            assertThat(outcome).describedAs(basis.name).isEqualTo(expected)
            // 관측은 어느 쪽이든 남는다 — 갈리는 것은 상태 전이뿐이다 (D19).
            assertThat(plan.conditionFirstMetAt).describedAs(basis.name).isEqualTo(observedAt.plusSeconds(1))
            assertThat(plan.status).describedAs(basis.name).isEqualTo(
                if (expected == TradePreparationConditionOutcome.ARMED) {
                    TradePreparationStatus.ARMED
                } else {
                    TradePreparationStatus.WATCHING
                },
            )
        }
    }

    /** [basis] 로 결속한 `WATCHING` 계획이다. 목표 프리미엄은 3.00% 로 고정한다. */
    private fun watchingPlan(basis: BalanceBasis): TradePreparation {
        val plan = TradePreparation.create(
            TradePreparationSpec(
                ownerId = 1L,
                pair = MarketPair(Symbol("BTC"), Exchange.BITHUMB, Exchange.BINANCE),
                boundBalanceSnapshotId = "snapshot-1",
                boundBalanceBasis = basis,
                referenceForeignPrice = BigDecimal("70000"),
                referenceFxRate = BigDecimal("1400"),
                referencePremiumRate = BigDecimal("3.50"),
                referenceObservedAt = observedAt,
                referenceFxSource = Exchange.FX_PROVIDER,
                referenceFxObservedAt = observedAt,
                quantity = BigDecimal("0.1"),
                leverage = BigDecimal("3"),
            ),
        )
        plan.registerTarget(
            desiredEntryPremiumRate = BigDecimal("3.00"),
            boundBalanceSnapshotId = "snapshot-1",
            boundBalanceBasis = basis,
            at = observedAt,
        )
        return plan
    }

    private fun snapshot(basis: BalanceBasis, id: String = "snapshot-1"): BalanceSnapshot =
        BalanceSnapshot(
            id = id,
            koreaBalance = BigDecimal("24000000"),
            foreignBalance = BigDecimal("3500"),
            balanceBasis = basis,
            observedAt = observedAt,
        )
}
