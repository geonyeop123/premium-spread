package io.premiumspread.domain.tradeprep

import io.premiumspread.domain.market.MarketPair
import io.premiumspread.domain.premium.PremiumSnapshot
import io.premiumspread.domain.ticker.Exchange
import io.premiumspread.domain.ticker.Symbol
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

/**
 * AC17 의 Domain 절반 — 신선도(D14)와 조건부 전이(D19)의 경계값을 순수 unit 으로 고정한다.
 * scheduler → Job → 전이 경로 자체는 `:apps:batch` 의
 * `TradePreparationEvaluationJobIntegrationTest` 가 검증한다.
 */
class TradePreparationEvaluationServiceTest {

    private val now: Instant = Instant.parse("2026-08-30T00:00:10Z")
    private val pair: MarketPair = MarketPair.default(Symbol("BTC"))
    private val otherPair: MarketPair = MarketPair(Symbol("ETH"), Exchange.BITHUMB, Exchange.BINANCE)
    private val maxAge: Duration = Duration.ofSeconds(10)

    @Test
    fun `maxAge는 양수여야 한다`() {
        assertThatThrownBy { TradePreparationFreshnessPolicy(Duration.ZERO) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { TradePreparationFreshnessPolicy(Duration.ofSeconds(-1)) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `현재 관측값이 없으면 계획을 건드리지 않고 WATCHING으로 남긴다`() {
        val plan = watchingPlan()
        val repository = FakeTradePreparationRepository(plan)

        val summary = service(repository).evaluate(pair, premium = null, now = now)

        assertThat(summary.outcome).isEqualTo(TradePreparationEvaluationOutcome.STREAM_UNAVAILABLE)
        assertThat(repository.watchingQueries).isZero()
        assertUntouchedWatching(plan)
    }

    @Test
    fun `관측값의 MarketPair가 다르면 다른 pair 값으로 보정하지 않는다`() {
        val plan = watchingPlan()
        val repository = FakeTradePreparationRepository(plan)

        val summary = service(repository).evaluate(pair, premium(rate = "0.10", pair = otherPair), now)

        assertThat(summary.outcome).isEqualTo(TradePreparationEvaluationOutcome.PAIR_MISMATCH)
        assertThat(repository.watchingQueries).isZero()
        assertUntouchedWatching(plan)
    }

    @Test
    fun `관측 시각이 maxAge보다 과거면 조건을 충족해도 WATCHING을 유지한다`() {
        val plan = watchingPlan()
        val repository = FakeTradePreparationRepository(plan)

        val summary = service(repository).evaluate(pair, premium(rate = "0.10", observedAt = now.minusSeconds(11)), now)

        assertThat(summary.outcome).isEqualTo(TradePreparationEvaluationOutcome.STALE_OBSERVATION)
        assertUntouchedWatching(plan)
    }

    /** 생산자 clock skew 로 미래가 된 관측값은 age 가 음수라 단방향 판정에서는 "신선"으로 통과한다. */
    @Test
    fun `관측 시각이 미래면 조건을 충족해도 WATCHING을 유지한다`() {
        val plan = watchingPlan()
        val repository = FakeTradePreparationRepository(plan)

        val summary = service(repository).evaluate(pair, premium(rate = "0.10", observedAt = now.plusMillis(1)), now)

        assertThat(summary.outcome).isEqualTo(TradePreparationEvaluationOutcome.STALE_OBSERVATION)
        assertUntouchedWatching(plan)
    }

    @Test
    fun `maxAge 경계와 현재 시각은 신선하다`() {
        val policy = TradePreparationFreshnessPolicy(maxAge)

        assertThat(policy.isFresh(now.minus(maxAge), now)).isTrue()
        assertThat(policy.isFresh(now, now)).isTrue()
        assertThat(policy.isFresh(now.minus(maxAge).minusMillis(1), now)).isFalse()
        assertThat(policy.isFresh(now.plusMillis(1), now)).isFalse()
    }

    @Test
    fun `신선한 관측값이 목표에 도달하고 결속이 verified면 ARMED로 전이한다`() {
        val plan = watchingPlan(basis = BalanceBasis.FRESH)
        val repository = FakeTradePreparationRepository(plan)
        val observedAt = now.minusSeconds(3)

        val summary = service(repository).evaluate(pair, premium(rate = "1.00", observedAt = observedAt), now)

        assertThat(summary.outcome).isEqualTo(TradePreparationEvaluationOutcome.EVALUATED)
        assertThat(summary.evaluated).isEqualTo(1)
        assertThat(summary.armed).isEqualTo(1)
        assertThat(summary.observedOnly).isZero()
        assertThat(plan.status).isEqualTo(TradePreparationStatus.ARMED)
        assertThat(plan.conditionFirstMetAt).isEqualTo(observedAt)
        assertThat(repository.saved).containsExactly(plan)
    }

    @Test
    fun `UNVERIFIED 결속은 조건을 충족해도 WATCHING을 유지하고 관측만 기록한다`() {
        val plan = watchingPlan(basis = BalanceBasis.UNVERIFIED)
        val repository = FakeTradePreparationRepository(plan)
        val observedAt = now.minusSeconds(3)

        val summary = service(repository).evaluate(pair, premium(rate = "1.00", observedAt = observedAt), now)

        assertThat(summary.outcome).isEqualTo(TradePreparationEvaluationOutcome.EVALUATED)
        assertThat(summary.armed).isZero()
        assertThat(summary.observedOnly).isEqualTo(1)
        assertThat(plan.status).isEqualTo(TradePreparationStatus.WATCHING)
        assertThat(plan.conditionFirstMetAt).isEqualTo(observedAt)
        assertThat(plan.conditionFirstMetPremiumRate).isEqualByComparingTo(BigDecimal("1.00"))
        assertThat(repository.saved).containsExactly(plan)
    }

    @Test
    fun `프리미엄이 목표보다 높으면 아무 것도 기록하지 않는다`() {
        val plan = watchingPlan(basis = BalanceBasis.FRESH)
        val repository = FakeTradePreparationRepository(plan)

        val summary = service(repository).evaluate(pair, premium(rate = "3.00", observedAt = now.minusSeconds(1)), now)

        assertThat(summary.outcome).isEqualTo(TradePreparationEvaluationOutcome.EVALUATED)
        assertThat(summary.evaluated).isEqualTo(1)
        assertThat(summary.armed).isZero()
        assertThat(summary.observedOnly).isZero()
        assertThat(repository.saved).isEmpty()
        assertUntouchedWatching(plan)
    }

    @Test
    fun `평가 대상은 같은 pair의 WATCHING 계획뿐이다`() {
        val repository = FakeTradePreparationRepository()

        val summary = service(repository).evaluate(pair, premium(rate = "1.00", observedAt = now), now)

        assertThat(summary.outcome).isEqualTo(TradePreparationEvaluationOutcome.EVALUATED)
        assertThat(summary.evaluated).isZero()
        assertThat(repository.watchingQueries).isEqualTo(1)
        assertThat(repository.requestedPairs).containsExactly(pair)
    }

    @Test
    fun `EVALUATED는 평가 결과 없이 만들 수 없다`() {
        assertThatThrownBy {
            TradePreparationEvaluationSummary.notEvaluated(TradePreparationEvaluationOutcome.EVALUATED)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    private fun service(repository: TradePreparationRepository) =
        TradePreparationEvaluationService(repository, TradePreparationFreshnessPolicy(maxAge))

    private fun assertUntouchedWatching(plan: TradePreparation) {
        assertThat(plan.status).isEqualTo(TradePreparationStatus.WATCHING)
        assertThat(plan.conditionFirstMetAt).isNull()
        assertThat(plan.conditionFirstMetPremiumRate).isNull()
        assertThat(plan.invalidationReason).isNull()
        assertThat(plan.invalidatedAt).isNull()
    }

    private fun premium(
        rate: String,
        observedAt: Instant = now,
        pair: MarketPair = this.pair,
    ): PremiumSnapshot = PremiumSnapshot(
        pair = pair,
        premiumRate = BigDecimal(rate),
        koreaPrice = BigDecimal("129555000"),
        foreignPrice = BigDecimal("89500"),
        foreignPriceInKrw = BigDecimal("128217700"),
        fxRate = BigDecimal("1432.6"),
        observedAt = observedAt,
        fxSource = Exchange.FX_PROVIDER,
        fxObservedAt = observedAt,
    )

    private fun watchingPlan(basis: BalanceBasis = BalanceBasis.FRESH): TradePreparation {
        val plan = TradePreparation.create(
            TradePreparationSpec(
                ownerId = 1L,
                pair = pair,
                boundBalanceSnapshotId = "snap-1",
                boundBalanceBasis = basis,
                referenceForeignPrice = BigDecimal("89500"),
                referenceFxRate = BigDecimal("1432.6"),
                referencePremiumRate = BigDecimal("2.00"),
                referenceObservedAt = now,
                referenceFxSource = Exchange.FX_PROVIDER,
                referenceFxObservedAt = now,
                quantity = BigDecimal("0.5"),
                leverage = BigDecimal("2.0"),
            ),
        )
        plan.registerTarget(
            desiredEntryPremiumRate = BigDecimal("1.50"),
            boundBalanceSnapshotId = "snap-1",
            boundBalanceBasis = basis,
            at = now,
        )
        return plan
    }
}

/** 평가 순서와 저장 호출을 그대로 드러내는 in-memory 저장소다. */
private class FakeTradePreparationRepository(private vararg val watching: TradePreparation) : TradePreparationRepository {

    val saved = mutableListOf<TradePreparation>()
    val requestedPairs = mutableListOf<MarketPair>()
    var watchingQueries: Int = 0
        private set

    override fun save(plan: TradePreparation): TradePreparation = plan.also { saved += it }

    override fun findById(id: Long): TradePreparation? = null

    override fun findActiveByOwnerId(ownerId: Long): TradePreparation? = null

    override fun findAllWatchingByPair(pair: MarketPair): List<TradePreparation> {
        watchingQueries++
        requestedPairs += pair
        return watching.toList()
    }
}
