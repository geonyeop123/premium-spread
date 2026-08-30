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
import io.premiumspread.domain.tradeprep.TradePreparationInvalidationReason
import io.premiumspread.domain.tradeprep.TradePreparationRepository
import io.premiumspread.domain.tradeprep.TradePreparationSpec
import io.premiumspread.domain.tradeprep.TradePreparationStatus
import io.premiumspread.domain.tradeprep.VerifiedBalance
import io.premiumspread.domain.tradeprep.VerifiedBalanceReadPort
import io.premiumspread.interfaces.scheduling.BatchSchedulingProperties
import io.premiumspread.interfaces.scheduling.TradePreparationReconcileScheduler
import io.premiumspread.support.BatchIntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
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
 * AC18 — **scheduler → `JobExecutor` → 무효화 경로**로 D5 결속과 D17 reconcile producer 를
 * 검증한다.
 *
 * ## 왜 무효화 메서드를 직접 부르지 않는가
 *
 * AC18 이 명시적으로 배제한다: "무효화 메서드 직접 호출은 이 기준을 충족하지 않는다."
 * `TradePreparation.invalidateOnReconcileMismatch` 를 테스트가 직접 부르면 Job 이 판정용 port 를
 * 안 읽든 활성 계획 조회를 빠뜨리든 배선이 끊겨도 green 이다. 이 테스트의 유일한 시작점은
 * production scheduler 의 trigger 메서드다.
 *
 * ## scheduler 를 빈으로 만들되 timer 는 돌리지 않는다
 *
 * `TradePreparationEvaluationJobIntegrationTest` 와 같은 이유·같은 형태다 — `test` profile 은
 * `batch.scheduling.enabled=false` 라 `@ConditionalOnBatchScheduling` scheduler 가 스캔되지 않고,
 * `enabled=true` 로 켜면 모든 scheduler 가 배경에서 돌아 같은 Redis lock 을 두고 경쟁한다.
 *
 * ## 판정용 원천은 test 전용 빈이다
 *
 * production main classpath 에 판정용 구현을 두지 않는다 — AC20 이 깨지고 D22 가 무너진다.
 * 원천이 **없을 때**의 동작은 별도 context 가 필요해
 * `TradePreparationReconcileWithoutBalanceSourceIntegrationTest` 가 소유한다.
 */
@TestPropertySource(
    properties = [
        // 첫 JPA 질의의 warm-up 이 기본 timeout 을 스치지 않게 넉넉히 잡는다. 검증 대상은
        // timeout 이 아니라 전이다.
        "batch.jobs.trade-preparation-reconcile.lease=60s",
        "batch.jobs.trade-preparation-reconcile.execution-timeout=30s",
    ],
)
@Import(TradePreparationReconcileJobIntegrationTest.ReconcileSchedulerConfig::class)
class TradePreparationReconcileJobIntegrationTest : BatchIntegrationTestBase() {

    @Autowired private lateinit var scheduler: TradePreparationReconcileScheduler

    @Autowired private lateinit var memberRepository: MemberRepository

    @Autowired private lateinit var planRepository: TradePreparationRepository

    @Autowired private lateinit var balanceSource: MutableVerifiedBalanceSource

    /**
     * batch 통합 테스트의 스키마는 Flyway 가 아니라 Entity(`ddl-auto: create-drop`)에서 나온다.
     * `V17` 이 만드는 인덱스를 Entity `@Table` 도 선언해야 두 스키마가 같아진다 — 어긋나면 batch
     * 는 인덱스 없는 테이블에서, production 은 있는 테이블에서 돌게 되고 조회 순서 가정(평가
     * 질의의 `ORDER BY t.id ASC`)이 검증된 적 없는 상태가 된다.
     *
     * 컬럼 순서까지 본다 — reconcile 조회는 `status` 1컬럼 prefix 로 이 인덱스를 탄다.
     */
    @Test
    fun `Entity 스키마가 V17 평가 인덱스를 같은 컬럼 순서로 갖는다`() {
        val indexes = jdbcTemplate.queryForList(
            """
            SELECT INDEX_NAME, SEQ_IN_INDEX, COLUMN_NAME FROM information_schema.STATISTICS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'trade_preparation'
            ORDER BY INDEX_NAME, SEQ_IN_INDEX
            """.trimIndent(),
        )
        val columns = indexes
            .filter { it["INDEX_NAME"] == "idx_trade_preparation_status_pair" }
            .map { it["COLUMN_NAME"] as String }

        assertThat(columns)
            .describedAs("trade_preparation indexes: %s", indexes)
            .containsExactly("status", "symbol", "korea_exchange", "foreign_exchange")
    }

    @Test
    fun `결속 스냅샷과 현재 판정용 잔고가 다르면 WATCHING 계획을 무효화한다`() {
        val planId = activePlan(boundSnapshotId = "snap-bound")
        balanceSource.snapshotId = "snap-current"

        scheduler.reconcile()

        assertInvalidated(planId)
    }

    @Test
    fun `결속 스냅샷과 현재 판정용 잔고가 다르면 ARMED 계획도 무효화한다`() {
        val planId = activePlan(boundSnapshotId = "snap-bound", arm = true)
        balanceSource.snapshotId = "snap-current"
        assertThat(reload(planId).status).isEqualTo(TradePreparationStatus.ARMED)

        scheduler.reconcile()

        assertInvalidated(planId)
    }

    @Test
    fun `결속 스냅샷과 현재 판정용 잔고가 같으면 상태를 바꾸지 않는다`() {
        val watchingId = activePlan(boundSnapshotId = "snap-bound")
        val armedId = activePlan(boundSnapshotId = "snap-bound", email = "armed-owner@example.com", arm = true)
        balanceSource.snapshotId = "snap-bound"

        scheduler.reconcile()

        assertUnchanged(watchingId, TradePreparationStatus.WATCHING)
        assertUnchanged(armedId, TradePreparationStatus.ARMED)
    }

    /**
     * 한 사이클이 활성 계획을 **전부** 다루면서 일치하는 것은 건드리지 않는다. 계획을 하나만
     * 심으면 "첫 계획만 처리한다"도, "전부 무효화한다"도 통과한다.
     */
    @Test
    fun `한 사이클에서 불일치 계획만 골라 무효화한다`() {
        val matchedId = activePlan(boundSnapshotId = "snap-current")
        val mismatchedId = activePlan(boundSnapshotId = "snap-bound", email = "mismatched-owner@example.com")
        val mismatchedArmedId = activePlan(
            boundSnapshotId = "snap-older",
            email = "mismatched-armed-owner@example.com",
            arm = true,
        )
        balanceSource.snapshotId = "snap-current"

        scheduler.reconcile()

        assertUnchanged(matchedId, TradePreparationStatus.WATCHING)
        assertInvalidated(mismatchedId)
        assertInvalidated(mismatchedArmedId)
    }

    /** `INVALIDATED` 는 종점이다 (D11) — 활성이 아니므로 reconcile 대상 자체가 아니다. */
    @Test
    fun `이미 무효화된 계획은 다시 무효화하지 않는다`() {
        val planId = activePlan(boundSnapshotId = "snap-bound")
        balanceSource.snapshotId = "snap-current"
        scheduler.reconcile()
        val firstPass = reload(planId)
        val invalidatedVersion = firstPass.version
        assertThat(firstPass.status).isEqualTo(TradePreparationStatus.INVALIDATED)

        scheduler.reconcile()

        val secondPass = reload(planId)
        assertThat(secondPass.status).isEqualTo(TradePreparationStatus.INVALIDATED)
        assertThat(secondPass.version).isEqualTo(invalidatedVersion)
        assertThat(secondPass.invalidatedAt).isEqualTo(NOW)
    }

    /**
     * 원천은 배선돼 있으나 판정용 잔고를 주지 못한 경우다(조회 실패). 대조하지 못한 것과 불일치를
     * 발견한 것은 다른 사실이므로 계획은 그대로 남는다.
     */
    @Test
    fun `원천이 판정용 잔고를 주지 못하면 계획을 무효화하지 않는다`() {
        val planId = activePlan(boundSnapshotId = "snap-bound")
        balanceSource.snapshotId = null

        scheduler.reconcile()

        assertUnchanged(planId, TradePreparationStatus.WATCHING)
    }

    /**
     * `UNVERIFIED` 결속(production 이 실제로 도달하는 상태, D20)도 대조 대상이다 — 판정용 잔고와
     * id 가 다르면 무효화한다. 결속의 검증 수준은 `ARMED` 관문이 쓰는 것이지 reconcile 의 기준이
     * 아니다.
     */
    @Test
    fun `UNVERIFIED 결속 계획도 스냅샷 id 가 다르면 무효화한다`() {
        val planId = activePlan(boundSnapshotId = "declared-snap", basis = BalanceBasis.UNVERIFIED)
        balanceSource.snapshotId = "snap-current"

        scheduler.reconcile()

        assertInvalidated(planId)
    }

    private fun assertInvalidated(planId: Long) {
        val plan = reload(planId)
        assertThat(plan.status).isEqualTo(TradePreparationStatus.INVALIDATED)
        assertThat(plan.invalidationReason).isEqualTo(TradePreparationInvalidationReason.RECONCILE_MISMATCH)
        assertThat(plan.invalidatedAt).isEqualTo(NOW)
    }

    private fun assertUnchanged(planId: Long, expected: TradePreparationStatus) {
        val plan = reload(planId)
        assertThat(plan.status).isEqualTo(expected)
        assertThat(plan.invalidationReason).isNull()
        assertThat(plan.invalidatedAt).isNull()
    }

    private fun reload(planId: Long): TradePreparation = planRepository.findById(planId)!!

    private fun activePlan(
        boundSnapshotId: String,
        email: String = "reconcile-owner@example.com",
        basis: BalanceBasis = BalanceBasis.FRESH,
        arm: Boolean = false,
    ): Long {
        val ownerId = memberRepository.save(Member.create(email, "encoded-password")).id
        val plan = TradePreparation.create(
            TradePreparationSpec(
                ownerId = ownerId,
                pair = MARKET_PAIR,
                boundBalanceSnapshotId = boundSnapshotId,
                boundBalanceBasis = basis,
                referenceForeignPrice = FOREIGN_PRICE,
                referenceFxRate = FX_RATE,
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
            boundBalanceSnapshotId = boundSnapshotId,
            boundBalanceBasis = basis,
            at = NOW,
        )
        // ARMED 는 조건 평가의 산물이다. 상태를 손으로 UPDATE 하지 않고 같은 전이 경로로 만든다 —
        // 그래야 reconcile 이 실제 production 상태를 대조한다.
        if (arm) plan.evaluateCondition(DESIRED_ENTRY_PREMIUM_RATE, NOW)
        return planRepository.save(plan).id
    }

    /**
     * 판정용 원천의 test 전용 대역이다. `domain/src/test` 의 `RecordedBalanceAdapter` 와 같은 역할
     * 이지만 그 source set 은 이 모듈에서 보이지 않고, 여기서는 테스트마다 반환 스냅샷을 바꿔야
     * 한다. `null` 은 "원천은 있으나 판정용 잔고를 얻지 못함"이다.
     */
    class MutableVerifiedBalanceSource : VerifiedBalanceReadPort {
        @Volatile
        var snapshotId: String? = null

        override fun findForDecision(): VerifiedBalance? = snapshotId?.let { id ->
            VerifiedBalance.from(
                BalanceSnapshot(
                    id = id,
                    koreaBalance = BigDecimal("1000000"),
                    foreignBalance = BigDecimal("700"),
                    balanceBasis = BalanceBasis.FRESH,
                    observedAt = NOW,
                ),
            )
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    class ReconcileSchedulerConfig {
        @Bean
        fun tradePreparationReconcileScheduler(
            job: TradePreparationReconcileJob,
            scheduling: BatchSchedulingProperties,
        ): TradePreparationReconcileScheduler = TradePreparationReconcileScheduler(job, scheduling)

        @Bean
        fun mutableVerifiedBalanceSource(): MutableVerifiedBalanceSource = MutableVerifiedBalanceSource()

        @Bean
        @Primary
        fun fixedReconcileClock(): Clock = Clock.fixed(NOW, ZoneOffset.UTC)
    }

    companion object {
        val NOW: Instant = Instant.parse("2026-08-30T00:00:30Z")
        val MARKET_PAIR: MarketPair = MarketPair(Symbol("BTC"), Exchange.BITHUMB, Exchange.BINANCE)
        val FOREIGN_PRICE: BigDecimal = BigDecimal("89500")
        val FX_RATE: BigDecimal = BigDecimal("1432.6")
        val DESIRED_ENTRY_PREMIUM_RATE: BigDecimal = BigDecimal("1.50")
    }
}
