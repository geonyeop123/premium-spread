package io.premiumspread.application.job

import io.premiumspread.application.job.tradeprep.TradePreparationEvaluationJob
import io.premiumspread.domain.market.MarketPair
import io.premiumspread.domain.member.Member
import io.premiumspread.domain.member.MemberRepository
import io.premiumspread.domain.premium.PremiumSnapshot
import io.premiumspread.domain.ticker.Exchange
import io.premiumspread.domain.ticker.Symbol
import io.premiumspread.domain.tradeprep.BalanceBasis
import io.premiumspread.domain.tradeprep.TradePreparation
import io.premiumspread.domain.tradeprep.TradePreparationRepository
import io.premiumspread.domain.tradeprep.TradePreparationSpec
import io.premiumspread.domain.tradeprep.TradePreparationStatus
import io.premiumspread.infrastructure.common.cache.premium.PremiumCacheWriter
import io.premiumspread.interfaces.scheduling.BatchSchedulingProperties
import io.premiumspread.interfaces.scheduling.TradePreparationEvaluationScheduler
import io.premiumspread.redis.RedisKeyGenerator
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
 * AC17 — **scheduler → Job → 전이 경로**로 D14 신선도와 D19 결속 관문을 검증한다
 * (design.md D14·D19·D21).
 *
 * ## 왜 Domain 서비스를 직접 부르지 않는가
 *
 * `TradePreparationEvaluationService.evaluate` 를 테스트가 직접 부르면 Job 이 pair 를 잘못 넘기든
 * 읽기 port 를 안 부르든 배선이 끊겨도 green 이다. AC17 이 요구하는 것은 그 사슬 전체이므로
 * 이 테스트의 유일한 시작점은 production scheduler 의 trigger 메서드다.
 *
 * ## scheduler 를 빈으로 만들되 timer 는 돌리지 않는다
 *
 * `test` profile 은 `batch.scheduling.enabled=false` 라 `@ConditionalOnBatchScheduling` 인
 * scheduler 가 스캔되지 않는다. `enabled=true` 로 켜면 `@EnableScheduling` 과 함께 **모든**
 * scheduler 가 1초 timer 로 돌아, (1) 배경 실행이 같은 Redis lock 을 쥐어 이 테스트의 실행이
 * `Skipped("lock")` 으로 흘러가고 (2) 단일 scheduler thread 를 외부 호출 job 이 점유해 실행 시점이
 * 벽시계에 좌우된다. 그래서 [EvaluationSchedulerConfig] 가 production scheduler 클래스를 **실제
 * Job 빈으로** 조립해 등록하고(`@Scheduled` 는 `@EnableScheduling` 이 없어 무동작) 테스트가 trigger
 * 메서드를 직접 부른다. scheduler 가 production 배선에서 `batch.scheduling.enabled` 로 켜지고
 * 꺼지는 것은 `SchedulingDisabledContextTest`(unit)가 소유한다.
 *
 * 시각은 고정 [Clock] 이다 — 신선도 경계가 벽시계에 의존하면 이 테스트가 검증하는 것이 사라진다.
 */
@TestPropertySource(
    properties = [
        "trade-preparation.evaluation.max-age=10s",
        // 첫 JPA 질의의 warm-up 이 기본 3s timeout 을 스치지 않게 넉넉히 잡는다. 검증 대상은
        // timeout 이 아니라 전이다.
        "batch.jobs.trade-preparation-evaluation.lease=60s",
        "batch.jobs.trade-preparation-evaluation.execution-timeout=30s",
    ],
)
@Import(TradePreparationEvaluationJobIntegrationTest.EvaluationSchedulerConfig::class)
class TradePreparationEvaluationJobIntegrationTest : BatchIntegrationTestBase() {

    @Autowired private lateinit var scheduler: TradePreparationEvaluationScheduler

    @Autowired private lateinit var memberRepository: MemberRepository

    @Autowired private lateinit var planRepository: TradePreparationRepository

    @Autowired private lateinit var premiumCacheWriter: PremiumCacheWriter

    @Test
    fun `신선한 관측값이 목표에 도달하고 결속이 verified 면 ARMED 로 전이한다`() {
        val planId = watchingPlan(basis = BalanceBasis.FRESH)
        seedPremium(rate = "1.0000", observedAt = NOW.minusSeconds(3))

        scheduler.evaluate()

        val plan = reload(planId)
        assertThat(plan.status).isEqualTo(TradePreparationStatus.ARMED)
        assertThat(plan.conditionFirstMetAt).isEqualTo(NOW.minusSeconds(3))
        assertThat(plan.conditionFirstMetPremiumRate).isEqualByComparingTo(BigDecimal("1.00"))
    }

    /** `ARMED` 는 이 단위의 종점이다 — 전이가 주문·추적 기록을 만들지 않는다 (design.md D7). */
    @Test
    fun `ARMED 전이는 추적 기록을 만들지 않는다`() {
        val planId = watchingPlan(basis = BalanceBasis.FRESH)
        seedPremium(rate = "1.0000", observedAt = NOW)

        scheduler.evaluate()

        assertThat(reload(planId).status).isEqualTo(TradePreparationStatus.ARMED)
        // Tracking(진입 기록) entity 의 테이블이다. 전이가 포지션을 만들지 않았음을 원본 테이블로 본다.
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM position", Long::class.java)).isZero()
    }

    @Test
    fun `관측 시각이 MAX_AGE 보다 과거면 조건을 충족해도 WATCHING 을 유지한다`() {
        val planId = watchingPlan(basis = BalanceBasis.FRESH)
        seedPremium(rate = "1.0000", observedAt = NOW.minusSeconds(11))

        scheduler.evaluate()

        assertStillWatching(planId)
    }

    @Test
    fun `관측 시각이 미래면 조건을 충족해도 WATCHING 을 유지한다`() {
        val planId = watchingPlan(basis = BalanceBasis.FRESH)
        seedPremium(rate = "1.0000", observedAt = NOW.plusSeconds(5))

        scheduler.evaluate()

        assertStillWatching(planId)
    }

    @Test
    fun `stream 최신값이 없으면 계획을 무효화하지 않고 WATCHING 으로 남긴다`() {
        val planId = watchingPlan(basis = BalanceBasis.FRESH)
        // 프리미엄 캐시를 심지 않는다. base 의 flushAll 이 이미 비워 뒀다.

        scheduler.evaluate()

        assertStillWatching(planId)
    }

    /**
     * 요청 pair 와 payload pair 가 다르면 miss 다 — 다른 pair 의 값으로 보정하지 않는다
     * (`.ai/rules/architecture.md`). 캐시 payload 를 직접 심어 그 경계를 강제한다.
     */
    @Test
    fun `캐시 payload 의 pair 가 다르면 관측값 없음으로 처리한다`() {
        val planId = watchingPlan(basis = BalanceBasis.FRESH)
        seedPremiumPayloadWithForeignPair(rate = "1.0000", observedAt = NOW)

        scheduler.evaluate()

        assertStillWatching(planId)
    }

    @Test
    fun `다른 MarketPair 의 계획은 이 pair 의 프리미엄으로 무장하지 않는다`() {
        val otherPairPlanId = watchingPlan(
            basis = BalanceBasis.FRESH,
            email = "other-pair-owner@example.com",
            pair = MarketPair(Symbol("ETH"), Exchange.BITHUMB, Exchange.BINANCE),
        )
        seedPremium(rate = "1.0000", observedAt = NOW)

        scheduler.evaluate()

        assertStillWatching(otherPairPlanId)
    }

    @Test
    fun `UNVERIFIED 결속은 조건을 충족해도 WATCHING 을 유지하고 관측만 기록한다`() {
        val planId = watchingPlan(basis = BalanceBasis.UNVERIFIED)
        seedPremium(rate = "1.0000", observedAt = NOW.minusSeconds(2))

        scheduler.evaluate()

        val plan = reload(planId)
        assertThat(plan.status).isEqualTo(TradePreparationStatus.WATCHING)
        assertThat(plan.invalidationReason).isNull()
        assertThat(plan.conditionFirstMetAt).isEqualTo(NOW.minusSeconds(2))
        assertThat(plan.conditionFirstMetPremiumRate).isEqualByComparingTo(BigDecimal("1.00"))
    }

    @Test
    fun `프리미엄이 목표보다 높으면 관측도 기록하지 않는다`() {
        val planId = watchingPlan(basis = BalanceBasis.FRESH)
        seedPremium(rate = "3.0000", observedAt = NOW)

        scheduler.evaluate()

        assertStillWatching(planId)
    }

    private fun assertStillWatching(planId: Long) {
        val plan = reload(planId)
        assertThat(plan.status).isEqualTo(TradePreparationStatus.WATCHING)
        assertThat(plan.invalidationReason).isNull()
        assertThat(plan.invalidatedAt).isNull()
        assertThat(plan.conditionFirstMetAt).isNull()
        assertThat(plan.conditionFirstMetPremiumRate).isNull()
    }

    private fun reload(planId: Long): TradePreparation = planRepository.findById(planId)!!

    private fun watchingPlan(
        basis: BalanceBasis,
        email: String = "evaluation-owner@example.com",
        pair: MarketPair = MARKET_PAIR,
    ): Long {
        val ownerId = memberRepository.save(Member.create(email, "encoded-password")).id
        val plan = TradePreparation.create(
            TradePreparationSpec(
                ownerId = ownerId,
                pair = pair,
                boundBalanceSnapshotId = "snap-1",
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
            boundBalanceSnapshotId = "snap-1",
            boundBalanceBasis = basis,
            at = NOW,
        )
        return planRepository.save(plan).id
    }

    private fun seedPremium(rate: String, observedAt: Instant) {
        premiumCacheWriter.save(
            PremiumSnapshot(
                pair = MARKET_PAIR,
                premiumRate = BigDecimal(rate),
                koreaPrice = KOREA_PRICE,
                foreignPrice = FOREIGN_PRICE,
                foreignPriceInKrw = FOREIGN_PRICE.multiply(FX_RATE),
                fxRate = FX_RATE,
                observedAt = observedAt,
                fxSource = Exchange.FX_PROVIDER,
                fxObservedAt = observedAt,
            ),
        )
    }

    /**
     * 요청 pair 의 key 에 **다른 pair 를 주장하는** payload 를 심는다.
     *
     * payload 를 손으로 조립하지 않고 production writer 가 만든 것을 그대로 옮긴다 — schema
     * 버전·필드 이름을 테스트가 다시 적으면 payload 계약이 바뀌었을 때 이 테스트만 조용히
     * 낡는다(그러면 pair 불일치가 아니라 parse 실패를 검증하게 된다).
     */
    private fun seedPremiumPayloadWithForeignPair(rate: String, observedAt: Instant) {
        val foreignPair = MarketPair(MARKET_PAIR.symbol, Exchange.UPBIT, Exchange.BINANCE)
        premiumCacheWriter.save(
            PremiumSnapshot(
                pair = foreignPair,
                premiumRate = BigDecimal(rate),
                koreaPrice = KOREA_PRICE,
                foreignPrice = FOREIGN_PRICE,
                foreignPriceInKrw = FOREIGN_PRICE.multiply(FX_RATE),
                fxRate = FX_RATE,
                observedAt = observedAt,
                fxSource = Exchange.FX_PROVIDER,
                fxObservedAt = observedAt,
            ),
        )
        val payload = redisTemplate.opsForHash<String, String>().entries(premiumKey(foreignPair))
        check(payload.isNotEmpty()) { "premium payload fixture must exist" }
        redisTemplate.opsForHash<String, String>().putAll(premiumKey(MARKET_PAIR), payload)
    }

    private fun premiumKey(pair: MarketPair): String = RedisKeyGenerator.premiumV2Key(
        pair.koreaExchange.name,
        pair.foreignExchange.name,
        pair.symbol.code,
    )

    @TestConfiguration(proxyBeanMethods = false)
    class EvaluationSchedulerConfig {
        @Bean
        fun tradePreparationEvaluationScheduler(
            job: TradePreparationEvaluationJob,
            scheduling: BatchSchedulingProperties,
        ): TradePreparationEvaluationScheduler = TradePreparationEvaluationScheduler(job, scheduling)

        @Bean
        @Primary
        fun fixedEvaluationClock(): Clock = Clock.fixed(NOW, ZoneOffset.UTC)
    }

    companion object {
        val NOW: Instant = Instant.parse("2026-08-30T00:00:30Z")
        val MARKET_PAIR: MarketPair = MarketPair(Symbol("BTC"), Exchange.BITHUMB, Exchange.BINANCE)
        val KOREA_PRICE: BigDecimal = BigDecimal("129555000")
        val FOREIGN_PRICE: BigDecimal = BigDecimal("89500")
        val FX_RATE: BigDecimal = BigDecimal("1432.6")

        /** 진입 목표. 이 값 **이하**로 내려와야 조건 충족이다 (design.md D7). */
        val DESIRED_ENTRY_PREMIUM_RATE: BigDecimal = BigDecimal("1.50")
    }
}
