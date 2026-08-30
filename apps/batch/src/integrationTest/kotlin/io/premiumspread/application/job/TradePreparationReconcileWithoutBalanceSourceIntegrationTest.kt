package io.premiumspread.application.job

import io.premiumspread.application.common.JobResult
import io.premiumspread.application.job.tradeprep.TradePreparationReconcileJob
import io.premiumspread.domain.market.MarketPair
import io.premiumspread.domain.member.Member
import io.premiumspread.domain.member.MemberRepository
import io.premiumspread.domain.ticker.Exchange
import io.premiumspread.domain.ticker.Symbol
import io.premiumspread.domain.tradeprep.BalanceBasis
import io.premiumspread.domain.tradeprep.TradePreparation
import io.premiumspread.domain.tradeprep.TradePreparationRepository
import io.premiumspread.domain.tradeprep.TradePreparationSpec
import io.premiumspread.domain.tradeprep.TradePreparationStatus
import io.premiumspread.domain.tradeprep.VerifiedBalanceReadPort
import io.premiumspread.interfaces.scheduling.BatchSchedulingProperties
import io.premiumspread.interfaces.scheduling.TradePreparationReconcileScheduler
import io.premiumspread.support.BatchIntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.test.context.TestPropertySource
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * **판정용 원천이 배선되지 않은 context** 다 — 현재 production 의 정상 상태이자
 * `TradePreparationReconcileJobIntegrationTest` 가 덮을 수 없는 나머지 한 경우다 (design.md D22).
 *
 * "원천 없음"은 빈이 **존재하지 않는** 상태이므로 같은 context 에서 재현할 수 없다. `null` 을
 * 돌려주는 빈은 다른 사실("원천은 있으나 조회 실패")이고, 그쪽은 저 테스트가 덮는다.
 *
 * 여기서 고정하는 것은 둘이다.
 * 1. batch production 배선에 [VerifiedBalanceReadPort] 구현이 **0개**다 (`apps:api` 의 AC20 과
 *    같은 경계를 batch 쪽에서 본다). 누군가 main classpath 에 판정용 구현을 추가하면 여기서 깨진다.
 * 2. 그 상태에서 Job 은 skipped 로 끝나고 **계획을 무효화하지 않는다.** failure 도 아니다 —
 *    설정이 아직 안 된 것이지 실행이 실패한 게 아니고, 대조하지 못한 것은 불일치를 발견한 것과
 *    다른 사실이다. 여기서 무효화하면 owner 의 계획이 미배선만으로 매 주기 사라진다.
 */
@TestPropertySource(
    properties = [
        "batch.jobs.trade-preparation-reconcile.lease=60s",
        "batch.jobs.trade-preparation-reconcile.execution-timeout=30s",
    ],
)
@Import(TradePreparationReconcileWithoutBalanceSourceIntegrationTest.NoBalanceSourceConfig::class)
class TradePreparationReconcileWithoutBalanceSourceIntegrationTest : BatchIntegrationTestBase() {

    @Autowired private lateinit var scheduler: TradePreparationReconcileScheduler

    @Autowired private lateinit var job: TradePreparationReconcileJob

    @Autowired private lateinit var memberRepository: MemberRepository

    @Autowired private lateinit var planRepository: TradePreparationRepository

    @Autowired private lateinit var context: ApplicationContext

    /** fixture 시점의 `version` 이다. reconcile 사이클이 지나도 그대로여야 "상태 불변"이다. */
    private val versionAtFixture = mutableMapOf<Long, Long>()

    @Test
    fun `batch 배선에는 판정용 잔고 원천 구현이 없다`() {
        assertThat(context.getBeansOfType(VerifiedBalanceReadPort::class.java)).isEmpty()
    }

    @Test
    fun `판정용 원천이 없으면 계획을 무효화하지 않고 skipped 로 끝낸다`() {
        val watchingId = activePlan(email = "no-source-watching@example.com")
        val armedId = activePlan(email = "no-source-armed@example.com", arm = true)

        scheduler.reconcile()

        assertUnchanged(watchingId, TradePreparationStatus.WATCHING)
        assertUnchanged(armedId, TradePreparationStatus.ARMED)
    }

    /**
     * scheduler 는 `JobResult` 를 돌려주지 않으므로(trigger 는 Job 을 한 번 부를 뿐이다) skipped
     * 사유는 Job 을 통해 본다. 위 테스트가 scheduler 경로로 상태 불변을 이미 고정했고, 여기서는
     * 그 결과가 failure 로 기록되지 않는다는 것을 본다.
     */
    @Test
    fun `판정용 원천 부재는 bounded skip 사유로 기록된다`() {
        val result = job.run()

        assertThat(result).isInstanceOf(JobResult.Skipped::class.java)
        assertThat((result as JobResult.Skipped).reason).isEqualTo("balance_source_unavailable")
    }

    private fun assertUnchanged(planId: Long, expected: TradePreparationStatus) {
        val plan = planRepository.findById(planId)!!
        assertThat(plan.status).isEqualTo(expected)
        assertThat(plan.invalidationReason).isNull()
        assertThat(plan.invalidatedAt).isNull()
        // 모든 전이가 증가시키는 business 카운터다(D11). "손대지 않았다"를 DB row 로 못 박는다.
        assertThat(plan.version).isEqualTo(versionAtFixture.getValue(planId))
    }

    private fun activePlan(email: String, arm: Boolean = false): Long {
        val ownerId = memberRepository.save(Member.create(email, "encoded-password")).id
        val plan = TradePreparation.create(
            TradePreparationSpec(
                ownerId = ownerId,
                pair = MARKET_PAIR,
                boundBalanceSnapshotId = "snap-bound",
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
            desiredEntryPremiumRate = DESIRED_ENTRY_PREMIUM_RATE,
            boundBalanceSnapshotId = "snap-bound",
            boundBalanceBasis = BalanceBasis.FRESH,
            at = NOW,
        )
        if (arm) plan.evaluateCondition(DESIRED_ENTRY_PREMIUM_RATE, NOW)
        val saved = planRepository.save(plan)
        versionAtFixture[saved.id] = saved.version
        return saved.id
    }

    /** 판정용 원천 빈을 **등록하지 않는다.** 그것이 이 context 의 존재 이유다. */
    @TestConfiguration(proxyBeanMethods = false)
    class NoBalanceSourceConfig {
        @Bean
        fun tradePreparationReconcileScheduler(
            job: TradePreparationReconcileJob,
            scheduling: BatchSchedulingProperties,
        ): TradePreparationReconcileScheduler = TradePreparationReconcileScheduler(job, scheduling)

        @Bean
        @Primary
        fun fixedReconcileClock(): Clock = Clock.fixed(NOW, ZoneOffset.UTC)
    }

    companion object {
        val NOW: Instant = Instant.parse("2026-08-30T00:00:30Z")
        val MARKET_PAIR: MarketPair = MarketPair(Symbol("BTC"), Exchange.BITHUMB, Exchange.BINANCE)
        val DESIRED_ENTRY_PREMIUM_RATE: BigDecimal = BigDecimal("1.50")
    }
}
