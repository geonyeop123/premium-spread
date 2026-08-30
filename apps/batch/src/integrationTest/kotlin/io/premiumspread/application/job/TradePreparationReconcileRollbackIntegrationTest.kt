package io.premiumspread.application.job

import io.premiumspread.application.job.tradeprep.TradePreparationReconcileJob
import io.premiumspread.domain.market.MarketPair
import io.premiumspread.domain.member.Member
import io.premiumspread.domain.member.MemberRepository
import io.premiumspread.domain.ticker.Exchange
import io.premiumspread.domain.ticker.Symbol
import io.premiumspread.domain.tradeprep.BalanceBasis
import io.premiumspread.domain.tradeprep.BalanceSnapshot
import io.premiumspread.domain.tradeprep.TradePreparation
import io.premiumspread.domain.tradeprep.TradePreparationRepository
import io.premiumspread.domain.tradeprep.TradePreparationSpec
import io.premiumspread.domain.tradeprep.TradePreparationStatus
import io.premiumspread.domain.tradeprep.VerifiedBalance
import io.premiumspread.domain.tradeprep.VerifiedBalanceReadPort
import io.premiumspread.infrastructure.common.persistence.jpa.tradeprep.JpaTradePreparationRepositoryAdapter
import io.premiumspread.interfaces.scheduling.BatchSchedulingProperties
import io.premiumspread.interfaces.scheduling.TradePreparationReconcileScheduler
import io.premiumspread.support.BatchIntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.test.context.TestPropertySource
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * reconcile 한 사이클의 **트랜잭션 경계**를 고정한다 —
 * `TradePreparationReconcileService` 의 클래스 `@Transactional` 이 실제로 무엇을 하는지 재는
 * 유일한 테스트다.
 *
 * ## 왜 별도 클래스인가
 *
 * 이 테스트는 저장소를 실패시키는 `@Primary` 데코레이터를 context 에 넣어야 한다. AC18 클래스
 * ([TradePreparationReconcileJobIntegrationTest])에 함께 두면 그 클래스의 모든 테스트가 데코레이터를
 * 거치게 되어 "production 배선으로 검증한다"는 성격이 흐려진다. context 하나를 더 쓰는 대신
 * AC18 클래스는 손대지 않는다.
 *
 * ## 왜 저장소 데코레이터인가
 *
 * T7 의 같은 성격 테스트(`TradePreparationEvaluationJobIntegrationTest` 의 롤백 테스트)는
 * `desired_entry_premium_rate` 를 비워 loop 중간의 전이가 던지게 만든다. 무효화 경로에는 그렇게
 * 데이터로 깨뜨릴 자리가 없다 — `invalidateOnReconcileMismatch` 는 어떤 행 상태에서도 던지지
 * 않는다. 그래서 **loop 중간의 `save` 가 던진다**는 같은 형태를 저장소 쪽에서 만든다. 운영에서
 * 이 자리에 오는 것은 동시 API 무효화가 만든 `OptimisticLockException` 이고, 이쪽은 결정적이다.
 *
 * ## 왜 계획이 3건인가
 *
 * T7 과 같은 구성이다. 던지는 지점(2번째 `save`) 앞뒤로 불일치 계획을 하나씩 둬서, 조회 정렬
 * 방향이 어떻든 **언제나 정상 계획 하나가 먼저 저장된 뒤** 실패가 난다. 하나만 두면 "롤백됐다"와
 * "애초에 손대지 않았다"를 단언이 구분하지 못한다.
 */
@TestPropertySource(
    properties = [
        "batch.jobs.trade-preparation-reconcile.lease=60s",
        "batch.jobs.trade-preparation-reconcile.execution-timeout=30s",
    ],
)
@Import(TradePreparationReconcileRollbackIntegrationTest.FailingSaveConfig::class)
class TradePreparationReconcileRollbackIntegrationTest : BatchIntegrationTestBase() {

    @Autowired private lateinit var scheduler: TradePreparationReconcileScheduler

    @Autowired private lateinit var memberRepository: MemberRepository

    @Autowired private lateinit var planRepository: TradePreparationRepository

    @Autowired private lateinit var failingRepository: SaveInterceptingRepository

    /**
     * 데코레이터는 context 수명의 singleton 이다. fixture 저장도 이것을 거치므로 각 테스트가
     * fixture 를 심은 **뒤에** `startCounting` 으로 다시 0부터 센다 — 여기 `@BeforeEach` 는 앞
     * 테스트의 무장이 남지 않게 하는 안전장치다.
     */
    @BeforeEach
    fun disarmFailingRepository() {
        failingRepository.startCounting()
    }

    /**
     * 사이클 중간의 `save` 가 던지면 같은 사이클에서 이미 무효화된 계획도 함께 롤백된다.
     *
     * **현재 동작을 고정한다.** 이것을 결함으로 보지 않는 이유는 T7 과 같다 — 다음 tick 이 자가
     * 치유한다(계획은 여전히 활성이고 판정용 잔고도 그대로다). 영향은 한 주기 지연이지 잘못된
     * 상태가 아니다. 이 테스트는 나중에 이 동작이 바뀔 때 그것이 의도된 변경인지 사고인지
     * 구분하게 한다.
     */
    @Test
    fun `사이클 중간의 저장이 실패하면 같은 사이클의 앞선 무효화도 롤백된다`() {
        val earlierId = activePlan(email = "rollback-earlier@example.com")
        val brokenId = activePlan(email = "rollback-broken@example.com")
        val laterId = activePlan(email = "rollback-later@example.com")
        // fixture 저장 3건을 세지 않도록 여기서부터 다시 센다.
        failingRepository.startCounting(failAtAttempt = 2)

        scheduler.reconcile()

        // 실패가 실제로 loop 중간에서 났음을 먼저 못 박는다. 첫 save 에서 던졌다면 롤백할 것이
        // 없어 아래 단언이 공허하게 통과한다.
        assertThat(failingRepository.saveAttempts).isEqualTo(2)
        assertStillActive(earlierId)
        assertStillActive(brokenId)
        assertStillActive(laterId)
    }

    /** 무효화가 커밋되려면 이 데코레이터가 던지지 않아야 한다 — 대조군이다. */
    @Test
    fun `저장이 실패하지 않으면 같은 구성에서 세 계획 모두 무효화된다`() {
        val ids = listOf(
            activePlan(email = "rollback-control-1@example.com"),
            activePlan(email = "rollback-control-2@example.com"),
            activePlan(email = "rollback-control-3@example.com"),
        )
        failingRepository.startCounting()

        scheduler.reconcile()

        assertThat(failingRepository.saveAttempts).isEqualTo(3)
        ids.forEach { id ->
            assertThat(reload(id).status).isEqualTo(TradePreparationStatus.INVALIDATED)
        }
    }

    private fun assertStillActive(planId: Long) {
        val plan = reload(planId)
        assertThat(plan.status).isEqualTo(TradePreparationStatus.WATCHING)
        assertThat(plan.invalidationReason).isNull()
        assertThat(plan.invalidatedAt).isNull()
        // 롤백은 status 뿐 아니라 version 증가까지 되돌려야 한다 (D11).
        assertThat(plan.version).isEqualTo(VERSION_AFTER_REGISTER)
    }

    private fun reload(planId: Long): TradePreparation = planRepository.findById(planId)!!

    /** 전부 `snap-bound` 에 결속한다 — 원천이 `snap-current` 를 주므로 셋 다 불일치다. */
    private fun activePlan(email: String): Long {
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
            desiredEntryPremiumRate = BigDecimal("1.50"),
            boundBalanceSnapshotId = "snap-bound",
            boundBalanceBasis = BalanceBasis.FRESH,
            at = NOW,
        )
        return planRepository.save(plan).id
    }

    /**
     * 실제 adapter 에 위임하되 **무장된 경우** n 번째 `save` 에서 던진다. 위임 전에 던지므로 그
     * 계획은 애초에 flush 되지 않고, 앞서 flush 된 계획만 롤백 대상으로 남는다.
     *
     * 무장은 테스트가 명시적으로 켠다 — 기본값은 통과라 대조군 테스트가 같은 배선에서 돈다.
     */
    class SaveInterceptingRepository(private val delegate: JpaTradePreparationRepositoryAdapter) :
        TradePreparationRepository by delegate {

        @Volatile
        private var failAtAttempt: Int = 0

        @Volatile
        var saveAttempts: Int = 0
            private set

        /**
         * 카운터를 0으로 되돌리고 실패 지점을 정한다. [failAtAttempt] `0` 은 "던지지 않는다"이며
         * 그 상태에서는 순수 위임이라 대조군 테스트가 production 과 같은 경로로 돈다.
         */
        fun startCounting(failAtAttempt: Int = 0) {
            this.failAtAttempt = failAtAttempt
            saveAttempts = 0
        }

        override fun save(plan: TradePreparation): TradePreparation {
            saveAttempts++
            if (saveAttempts == failAtAttempt) {
                throw IllegalStateException("save failed for plan #$saveAttempts")
            }
            return delegate.save(plan)
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    class FailingSaveConfig {
        @Bean
        fun tradePreparationReconcileScheduler(
            job: TradePreparationReconcileJob,
            scheduling: BatchSchedulingProperties,
        ): TradePreparationReconcileScheduler = TradePreparationReconcileScheduler(job, scheduling)

        /**
         * 위임 대상을 `TradePreparationRepository` 가 아니라 concrete adapter 로 받는다 — port 로
         * 받으면 `@Primary` 인 자기 자신이 후보가 되어 순환한다.
         */
        @Bean
        @Primary
        fun failingTradePreparationRepository(
            delegate: JpaTradePreparationRepositoryAdapter,
        ): SaveInterceptingRepository = SaveInterceptingRepository(delegate)

        @Bean
        fun rollbackVerifiedBalanceSource(): VerifiedBalanceReadPort = VerifiedBalanceReadPort {
            VerifiedBalance.from(
                BalanceSnapshot(
                    id = "snap-current",
                    koreaBalance = BigDecimal("1000000"),
                    foreignBalance = BigDecimal("700"),
                    balanceBasis = BalanceBasis.FRESH,
                    observedAt = NOW,
                ),
            )
        }

        @Bean
        @Primary
        fun fixedRollbackClock(): Clock = Clock.fixed(NOW, ZoneOffset.UTC)
    }

    companion object {
        val NOW: Instant = Instant.parse("2026-08-30T00:00:30Z")
        val MARKET_PAIR: MarketPair = MarketPair(Symbol("BTC"), Exchange.BITHUMB, Exchange.BINANCE)

        /** `create`(0) → `registerTarget`(1). 무효화가 커밋됐다면 2가 된다. */
        const val VERSION_AFTER_REGISTER = 1L
    }
}
