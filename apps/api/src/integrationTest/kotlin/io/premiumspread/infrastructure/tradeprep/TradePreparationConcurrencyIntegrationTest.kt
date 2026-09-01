package io.premiumspread.infrastructure.tradeprep

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
import io.premiumspread.testcontainers.RedisTestContainersConfig
import io.premiumspread.utils.DatabaseCleanUp
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.math.BigDecimal
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * AC11(design.md D11·D16·D23) — 무효화와 조건 충족 평가가 동시에 일어나도 `INVALIDATED`가
 * `ARMED`로 되돌아가지 않는다. owner당 활성 계획(`WATCHING`·`ARMED`)은 어떤 교차에서도 최대
 * 하나다. **실제 DB 트랜잭션**(Testcontainers MySQL, V16의 `uk_trade_preparation_owner_active`와
 * `lock_version` 낙관적 잠금)으로 검증한다 — 단일 스레드 순차 호출로는 이 기준이 막으려는 phantom
 * 경쟁을 재현하지 못하므로 흉내내지 않는다.
 *
 * **왜 전용 컨테이너 + `ddl-auto: validate`인가.** 이 모듈의 나머지 `@SpringBootTest`
 * 통합테스트(`test` profile)는 `hibernate.ddl-auto: create-drop`을 쓴다 — Flyway가 먼저
 * migration을 적용해도 Hibernate가 곧바로 entity 테이블을 지우고 JPA 매핑만으로 다시 만든다.
 * `active_key`(STORED generated column)와 `uk_trade_preparation_owner_active`는 V16 SQL에만
 * 있고 어떤 `@Entity` 애노테이션으로도 표현되지 않으므로, 그 모드에서는 애초에 존재하지 않는다
 * (직접 실측: 순차 이중 `registerTarget`조차 예외 없이 둘 다 성공했다). 이 테스트만 별도
 * container + `ddl-auto: validate`로 실제 V16 스키마를 보존해야 AC11이 막으려는 걸 실제로 막는지
 * 검증할 수 있다 — 공유 컨테이너를 쓰면 다른 테스트의 create-drop 배선이 언제 실행되느냐에 따라
 * 이 제약이 사라졌다 나타났다 한다.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
@Import(RedisTestContainersConfig::class, io.premiumspread.config.TestConfig::class)
class TradePreparationConcurrencyIntegrationTest @Autowired constructor(
    private val tradePreparationRepository: TradePreparationRepository,
    private val memberRepository: MemberRepository,
    private val passwordEncoder: PasswordEncoder,
    private val databaseCleanUp: DatabaseCleanUp,
) {

    private var ownerId: Long = 0L

    @BeforeEach
    fun setUp() {
        databaseCleanUp.truncateAllTables()
        val member = memberRepository.save(
            Member.create(
                email = "trade-prep-concurrency@example.com",
                encodedPassword = passwordEncoder.encode("password123"),
            ),
        )
        ownerId = member.id
    }

    @Test
    fun `동시 registerTarget은 owner당 정확히 하나만 성공한다`() {
        val planA = tradePreparationRepository.save(draft())
        val planB = tradePreparationRepository.save(draft())

        val barrier = CyclicBarrier(2)
        val pool = Executors.newFixedThreadPool(2)
        val futures = listOf(planA.id, planB.id).map { id ->
            pool.submit(
                Callable {
                    barrier.await()
                    runCatching {
                        val plan = tradePreparationRepository.findById(id)!!
                        plan.registerTarget(
                            desiredEntryPremiumRate = BigDecimal("3.00"),
                            boundBalanceSnapshotId = "declared-1",
                            boundBalanceBasis = BalanceBasis.UNVERIFIED,
                            at = Instant.parse("2026-08-30T00:00:00Z"),
                        )
                        tradePreparationRepository.save(plan)
                        Unit
                    }
                },
            )
        }
        val outcomes = futures.map { it.get(10, TimeUnit.SECONDS) }
        pool.shutdown()

        assertThat(outcomes.count { it.isSuccess }).isEqualTo(1)
        assertThat(outcomes.count { it.isFailure }).isEqualTo(1)
        outcomes.filter { it.isFailure }.forEach {
            assertThat(it.exceptionOrNull()).isInstanceOf(DataIntegrityViolationException::class.java)
        }

        val active = tradePreparationRepository.findActiveByOwnerId(ownerId)
        assertThat(active).isNotNull
        assertThat(active!!.status).isEqualTo(TradePreparationStatus.WATCHING)
        assertThat(listOf(planA.id, planB.id)).contains(active.id)
    }

    @Test
    fun `ARMED 계획이 있으면 새 registerTarget이 DB 유일성으로 거절되고 DB가 변하지 않는다`() {
        val armed = tradePreparationRepository.save(draft())
        armed.registerTarget(
            desiredEntryPremiumRate = BigDecimal("3.00"),
            boundBalanceSnapshotId = "recorded-1",
            boundBalanceBasis = BalanceBasis.FRESH,
            at = Instant.parse("2026-08-30T00:00:00Z"),
        )
        tradePreparationRepository.save(armed)
        val reloadedArmed = tradePreparationRepository.findById(armed.id)!!
        reloadedArmed.evaluateCondition(
            currentPremiumRate = BigDecimal("1.00"),
            observedAt = Instant.parse("2026-08-30T00:00:01Z"),
        )
        tradePreparationRepository.save(reloadedArmed)
        assertThat(tradePreparationRepository.findById(armed.id)!!.status).isEqualTo(TradePreparationStatus.ARMED)

        val challenger = tradePreparationRepository.save(draft())
        challenger.registerTarget(
            desiredEntryPremiumRate = BigDecimal("2.00"),
            boundBalanceSnapshotId = "declared-2",
            boundBalanceBasis = BalanceBasis.UNVERIFIED,
            at = Instant.parse("2026-08-30T00:00:02Z"),
        )

        assertThatThrownBy { tradePreparationRepository.save(challenger) }
            .isInstanceOf(DataIntegrityViolationException::class.java)

        val persistedChallenger = tradePreparationRepository.findById(challenger.id)!!
        assertThat(persistedChallenger.status).isEqualTo(TradePreparationStatus.DRAFT)
    }

    @Test
    fun `무효화와 조건 충족 평가가 동시에 일어나도 INVALIDATED가 ARMED로 되돌아가지 않는다`() {
        val saved = tradePreparationRepository.save(draft())
        saved.registerTarget(
            desiredEntryPremiumRate = BigDecimal("3.00"),
            boundBalanceSnapshotId = "recorded-2",
            boundBalanceBasis = BalanceBasis.FRESH,
            at = Instant.parse("2026-08-30T00:00:00Z"),
        )
        tradePreparationRepository.save(saved)

        // 두 스레드가 "같은 버전"(lock_version)의 엔티티를 각자 로드한 뒤에만 갈라지도록
        // 두 barrier로 강제한다 — 그래야 두 write가 진짜로 같은 시작점에서 경합한다. 한쪽이
        // 먼저 끝난 뒤 다른 쪽이 시작하면 두 번째는 이미 갱신된 버전을 읽어 경쟁 자체가
        // 사라진다.
        val loadBarrier = CyclicBarrier(2)
        val commitBarrier = CyclicBarrier(2)
        val pool = Executors.newFixedThreadPool(2)

        val invalidateFuture = pool.submit(
            Callable {
                loadBarrier.await()
                val copy = tradePreparationRepository.findById(saved.id)!!
                commitBarrier.await()
                runCatching {
                    copy.invalidateOnOwnerRefresh(Instant.parse("2026-08-30T00:00:10Z"))
                    tradePreparationRepository.save(copy)
                    copy.status
                }
            },
        )
        val armFuture = pool.submit(
            Callable {
                loadBarrier.await()
                val copy = tradePreparationRepository.findById(saved.id)!!
                commitBarrier.await()
                runCatching {
                    copy.evaluateCondition(
                        currentPremiumRate = BigDecimal("1.00"),
                        observedAt = Instant.parse("2026-08-30T00:00:10Z"),
                    )
                    tradePreparationRepository.save(copy)
                    copy.status
                }
            },
        )

        val invalidateOutcome = invalidateFuture.get(10, TimeUnit.SECONDS)
        val armOutcome = armFuture.get(10, TimeUnit.SECONDS)
        pool.shutdown()

        // 정확히 하나만 커밋에 성공한다 — 나머지는 stale 버전으로 낙관적 잠금 충돌을 낸다.
        assertThat(listOf(invalidateOutcome, armOutcome).count { it.isSuccess }).isEqualTo(1)
        listOf(invalidateOutcome, armOutcome).filter { it.isFailure }.forEach {
            assertThat(it.exceptionOrNull()).isInstanceOf(ObjectOptimisticLockingFailureException::class.java)
        }

        val final = tradePreparationRepository.findById(saved.id)!!
        if (invalidateOutcome.isSuccess) {
            assertThat(armOutcome.isFailure).isTrue()
            assertThat(final.status).isEqualTo(TradePreparationStatus.INVALIDATED)
        } else {
            assertThat(armOutcome.isSuccess).isTrue()
            assertThat(final.status).isEqualTo(TradePreparationStatus.ARMED)
        }
        // 핵심 불변조건: 두 write가 모두 반영되는 상태(= INVALIDATED가 ARMED로 되돌아감)는 없다.
        assertThat(final.status).isIn(TradePreparationStatus.INVALIDATED, TradePreparationStatus.ARMED)
    }

    private fun draft(): TradePreparation = TradePreparation.create(
        TradePreparationSpec(
            ownerId = ownerId,
            pair = MarketPair(Symbol("BTC"), Exchange.BITHUMB, Exchange.BINANCE),
            boundBalanceSnapshotId = "declared-draft",
            boundBalanceBasis = BalanceBasis.UNVERIFIED,
            referenceForeignPrice = BigDecimal("70000"),
            referenceFxRate = BigDecimal("1400"),
            referencePremiumRate = BigDecimal("3.50"),
            referenceObservedAt = Instant.parse("2026-08-30T00:00:00Z"),
            referenceFxSource = Exchange.FX_PROVIDER,
            referenceFxObservedAt = Instant.parse("2026-08-29T23:30:00Z"),
            quantity = BigDecimal("0.1"),
            leverage = BigDecimal("3"),
        ),
    )

    companion object {
        @Container
        @JvmStatic
        val mysql: MySQLContainer<*> = MySQLContainer("mysql:8.0")
            .withDatabaseName("premiumspread_trade_prep_concurrency")
            .withUsername("test")
            .withPassword("test")

        @JvmStatic
        @DynamicPropertySource
        fun mysqlProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") {
                mysql.jdbcUrl + "?sslMode=DISABLED&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true"
            }
            registry.add("spring.datasource.username") { mysql.username }
            registry.add("spring.datasource.password") { mysql.password }
            // 이 테스트만 실제 Flyway 스키마(V16의 generated column·unique index 포함)를 보존해야
            // 하므로, 나머지 모듈이 쓰는 test profile 기본값(create-drop)을 이 context에서만
            // validate로 덮어쓴다. Flyway가 이미 만든 스키마를 Hibernate가 지우지 않게 한다.
            registry.add("spring.jpa.hibernate.ddl-auto") { "validate" }
        }
    }
}
