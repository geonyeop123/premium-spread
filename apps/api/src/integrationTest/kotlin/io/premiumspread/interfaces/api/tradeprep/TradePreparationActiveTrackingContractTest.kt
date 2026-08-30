package io.premiumspread.interfaces.api.tradeprep

import com.fasterxml.jackson.databind.ObjectMapper
import io.premiumspread.config.TestConfig
import io.premiumspread.domain.market.MarketPair
import io.premiumspread.domain.member.Member
import io.premiumspread.domain.member.MemberRepository
import io.premiumspread.domain.premium.Premium
import io.premiumspread.domain.premium.PremiumRepository
import io.premiumspread.domain.ticker.Currency
import io.premiumspread.domain.ticker.Exchange
import io.premiumspread.domain.ticker.Quote
import io.premiumspread.domain.ticker.Symbol
import io.premiumspread.domain.ticker.Ticker
import io.premiumspread.domain.ticker.TickerRepository
import io.premiumspread.domain.tracking.Tracking
import io.premiumspread.domain.tracking.TrackingRecordSpec
import io.premiumspread.domain.tracking.TrackingRepository
import io.premiumspread.domain.tradeprep.BalanceBasis
import io.premiumspread.domain.tradeprep.BalanceSnapshot
import io.premiumspread.domain.tradeprep.TradePreparationRepository
import io.premiumspread.domain.tradeprep.TradePreparationStatus
import io.premiumspread.domain.tradeprep.VerifiedBalance
import io.premiumspread.domain.tradeprep.VerifiedBalanceReadPort
import io.premiumspread.testcontainers.RedisTestContainersConfig
import io.premiumspread.utils.DatabaseCleanUp
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.math.BigDecimal
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * AC16 — owner 의 `ACTIVE` tracking 이 존재하면 `prepare` 와 `registerTarget` 이 거절되고 DB 가
 * 변하지 않는다. **tracking 생성과 `registerTarget` 이 서로의 미커밋 상태를 못 보는 교차 순서를
 * 강제해도** owner member 행 잠금이 직렬화해 `ACTIVE` tracking 과 `WATCHING`·`ARMED` 계획이
 * 공존 커밋되지 않는다 (design.md D13·D17·D18).
 *
 * ## 순차 호출로는 이 기준을 충족하지 못한다
 *
 * 한쪽이 끝난 뒤 다른 쪽이 시작하면 두 번째는 첫 번째의 **커밋된** 상태를 읽으므로, 잠금이
 * 없어도 통과한다. 그래서 두 트랜잭션이 동시에 열려 있고 서로의 미커밋 상태를 보지 못하는
 * 교차를 양방향으로 강제한다. 각 방향에서 나중에 시작한 쪽이 member 행 잠금에 **막힌다**는 것을
 * `get(timeout)` 의 [TimeoutException] 으로 확인한다 — 막히지 않으면 두 검사가 서로를 못 본 채
 * 둘 다 커밋되는 write-skew 다.
 *
 * ## 왜 전용 container + `ddl-auto: validate` 인가
 *
 * `test` profile 은 `hibernate.ddl-auto: create-drop` 이라(`modules/jpa/src/main/resources/jpa.yml`)
 * Flyway 가 적용한 스키마를 Hibernate 가 지우고 entity 매핑만으로 다시 만든다. V16 의
 * `active_key`(STORED generated column)와 `uk_trade_preparation_owner_active` 는 어떤 JPA
 * 애너테이션으로도 표현되지 않아 그 스키마에 **존재하지 않는다** — 그대로 두면 제약 없이 통과하는
 * 거짓 green 이 된다. `TradePreparationConcurrencyIntegrationTest` 와 같은 패턴이다.
 *
 * ## `VerifiedBalanceReadPort` 를 동기화 지점으로 쓰는 이유
 *
 * `registerTarget` 을 **자기 트랜잭션 안에서** 멈춰 세워야 반대 방향 교차를 만들 수 있다.
 * [GatedVerifiedBalanceReadPort] 는 `resolveBinding` 단계, 즉 member 잠금(①)과 `ACTIVE` 재검사(②)
 * **뒤**에 딱 한 번 불리는 유일한 선택적 seam 이다. production 배선에는 이 빈이 없으므로(D22)
 * 이 context 는 production 과 다르며, 그 차이는 "멈출 지점" 하나뿐이다 — 잠금 순서도 검사 순서도
 * 이 빈이 바꾸지 않는다. 빗장이 걸려 있지 않으면 그냥 `FRESH` 를 돌려준다.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(
    RedisTestContainersConfig::class,
    TestConfig::class,
    TradePreparationActiveTrackingContractTest.GatedBalanceConfig::class,
)
class TradePreparationActiveTrackingContractTest {

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var databaseCleanUp: DatabaseCleanUp
    @Autowired private lateinit var memberRepository: MemberRepository
    @Autowired private lateinit var passwordEncoder: org.springframework.security.crypto.password.PasswordEncoder
    @Autowired private lateinit var tickerRepository: TickerRepository
    @Autowired private lateinit var premiumRepository: PremiumRepository
    @Autowired private lateinit var trackingRepository: TrackingRepository
    @Autowired private lateinit var tradePreparationRepository: TradePreparationRepository
    @Autowired private lateinit var transactionManager: PlatformTransactionManager
    @Autowired private lateinit var jdbcTemplate: JdbcTemplate
    @Autowired private lateinit var redisTemplate: StringRedisTemplate
    @Autowired private lateinit var gate: GatedVerifiedBalanceReadPort

    private var memberId: Long = 0L
    private lateinit var token: String
    private lateinit var pool: ExecutorService

    @BeforeEach
    fun setUp() {
        databaseCleanUp.truncateAllTables()
        // premium snapshot 조회는 Redis 캐시를 먼저 본다(JpaPremiumRepositoryAdapter). Redis
        // container 는 모듈 전체가 공유하므로, 비우지 않으면 다른 클래스가 남긴 낡은 observedAt
        // 이 recordFromMarket 의 신선도 판정을 흔든다.
        redisTemplate.execute { it.serverCommands().flushAll() }
        gate.disarm()
        pool = Executors.newFixedThreadPool(2)
        memberId = memberRepository.save(
            Member.create(email = EMAIL, encodedPassword = passwordEncoder.encode(PASSWORD)),
        ).id
        token = login()
        savePremium()
    }

    @AfterEach
    fun tearDown() {
        gate.release()
        pool.shutdownNow()
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue()
    }

    // ── 순차 거절 (AC16 첫 문장) ────────────────────────────────────────────

    @Test
    fun `ACTIVE tracking 이 있으면 prepare 가 409 로 거절되고 계획 행이 생기지 않는다`() {
        saveActiveTracking()

        val response = prepare()

        assertThat(response.status).isEqualTo(409)
        assertThat(errorCode(response)).isEqualTo("ACTIVE_TRACKING_EXISTS")
        assertThat(countPlans()).isZero()
    }

    @Test
    fun `ACTIVE tracking 이 있으면 registerTarget 이 409 로 거절되고 계획이 DRAFT 로 남는다`() {
        val planId = createDraftPlan()
        saveActiveTracking()

        val response = registerTarget(planId)

        assertThat(response.status).isEqualTo(409)
        assertThat(errorCode(response)).isEqualTo("ACTIVE_TRACKING_EXISTS")
        val plan = tradePreparationRepository.findById(planId)!!
        assertThat(plan.status).isEqualTo(TradePreparationStatus.DRAFT)
        assertThat(plan.desiredEntryPremiumRate).isNull()
        assertThat(tradePreparationRepository.findActiveByOwnerId(memberId)).isNull()
    }

    // ── 교차 순서 (AC16 둘째 문장) ──────────────────────────────────────────

    /**
     * 교차 ①: tracking 생성 트랜잭션이 먼저 열려 member 를 잠그고 `ACTIVE` tracking 을 **미커밋**
     * 상태로 들고 있는 동안 `registerTarget` 이 들어온다.
     *
     * 잠금이 없다면 `registerTarget` 의 `ACTIVE` 재검사는 미커밋 tracking 을 보지 못해 0 을 세고
     * 그대로 `WATCHING` 을 커밋한다 — `ACTIVE` tracking 과 활성 계획이 공존한다.
     */
    @Test
    fun `tracking 생성이 먼저 열린 교차에서 registerTarget 이 직렬화돼 거절된다`() {
        val planId = createDraftPlan()
        val trackingInserted = CountDownLatch(1)
        val commitTracking = CountDownLatch(1)

        val trackingFuture = pool.submit(
            Callable {
                TransactionTemplate(transactionManager).execute {
                    // TrackingFacade 와 같은 잠금 순서: member 를 먼저 잡고 tracking 을 쓴다.
                    memberRepository.findByIdForUpdate(memberId)
                    trackingRepository.save(activeTracking())
                    trackingInserted.countDown()
                    check(commitTracking.await(AWAIT_SECONDS, TimeUnit.SECONDS)) { "commit signal timed out" }
                }
                Unit
            },
        )
        check(trackingInserted.await(AWAIT_SECONDS, TimeUnit.SECONDS))

        val registerFuture = pool.submit(Callable { registerTarget(planId) })

        // 핵심 단언: registerTarget 은 member 행 잠금에서 막혀 끝나지 못한다. 막히지 않으면
        // 미커밋 tracking 을 못 본 채 통과해 WATCHING 을 커밋한다.
        assertThatThrownBy { registerFuture.get(BLOCKED_PROBE_SECONDS, TimeUnit.SECONDS) }
            .isInstanceOf(TimeoutException::class.java)

        commitTracking.countDown()
        trackingFuture.get(AWAIT_SECONDS, TimeUnit.SECONDS)

        val response = registerFuture.get(AWAIT_SECONDS, TimeUnit.SECONDS)
        assertThat(response.status).isEqualTo(409)
        assertThat(errorCode(response)).isEqualTo("ACTIVE_TRACKING_EXISTS")
        assertNoCoexistence(planId, expectedPlanStatus = TradePreparationStatus.DRAFT)
    }

    /**
     * 교차 ②: 반대 순서다. `registerTarget` 트랜잭션이 먼저 열려 member 를 잠그고 **미커밋**
     * 상태로 멈춰 있는 동안 tracking 생성 요청이 들어온다.
     *
     * 잠금이 없다면 tracking 생성은 미커밋 `WATCHING` 을 보지 못해 D17 의 무효화가 아무 것도
     * 찾지 못하고, 두 트랜잭션이 각각 커밋되어 공존한다. 잠금이 있으면 tracking 쪽이 막혔다가
     * 커밋된 `WATCHING` 을 보고 같은 트랜잭션에서 무효화한다.
     */
    @Test
    fun `registerTarget 이 먼저 열린 교차에서 tracking 생성이 직렬화돼 활성 계획을 무효화한다`() {
        val planId = createDraftPlan()
        gate.arm()

        val registerFuture = pool.submit(Callable { registerTarget(planId) })
        check(gate.awaitEntered(AWAIT_SECONDS)) { "registerTarget did not reach the gate" }

        val trackingFuture = pool.submit(Callable { recordTracking() })

        // 핵심 단언: tracking 생성은 member 행 잠금에서 막혀 끝나지 못한다.
        assertThatThrownBy { trackingFuture.get(BLOCKED_PROBE_SECONDS, TimeUnit.SECONDS) }
            .isInstanceOf(TimeoutException::class.java)

        gate.release()

        val registerResponse = registerFuture.get(AWAIT_SECONDS, TimeUnit.SECONDS)
        val trackingResponse = trackingFuture.get(AWAIT_SECONDS, TimeUnit.SECONDS)

        assertThat(registerResponse.status).isEqualTo(200)
        assertThat(trackingResponse.status).isEqualTo(201)
        // D17: tracking 생성이 같은 트랜잭션에서 활성 계획을 무효화했다.
        assertNoCoexistence(planId, expectedPlanStatus = TradePreparationStatus.INVALIDATED)
    }

    /**
     * 교차 ③: 교차 ②와 같은 순서이되 tracking 을 **production 경로**인 `recordFromMarket` 으로
     * 만든다 (`POST /api/v1/trackings/from-market`).
     *
     * **교차 ②로는 이 경로를 덮지 못한다.** `record` 는 `INSERT` 가 트랜잭션의 첫 DB 문장이라,
     * 애플리케이션 잠금이 없어도 `fk_position_member` 의 FK 검사가 부모 `member` 행에 공유
     * 잠금을 요청하며 대기하고, read view 도 그 뒤에 열려 커밋된 `WATCHING` 을 본다 — 즉 잠금을
     * 지워도 결과가 옳다. `recordFromMarket` 은 다르다: `premiumService.findLatestSnapshot` 이
     * `INSERT` 보다 앞서 consistent read 를 일으켜 **read view 가 먼저 열린다.** 그러면 FK 가
     * write 를 직렬화해도 `invalidateActiveOnTrackingEvent` 의 조회는 `registerTarget` 커밋 이전
     * 스냅샷을 읽어 빈손이 되고, `ACTIVE` tracking 과 `WATCHING` 계획이 공존 커밋된다.
     * 이 경로에서는 tracking 측 member 잠금(D18)이 유일한 방어다.
     *
     * ## 이 테스트가 조용히 무력화되는 조건 — 아래가 바뀌면 이 테스트를 함께 봐라
     *
     * 판별력이 **`findLatestSnapshot` 이 반드시 DB 질의를 낸다**는 사실에 의존한다.
     * `JpaPremiumRepositoryAdapter` 는 `PremiumCacheReader` 만 받고 miss 에 write-back 을 하지
     * 않으며, `PremiumCacheWriter` 를 쓰는 것은 `infrastructure:batch` 의 `PremiumCacheService`
     * 뿐이라 `apps:api` 런타임에는 premium 캐시를 채우는 코드가 없다. 그래서 이 조회가 항상
     * consistent read 를 일으켜 read view 가 `INSERT` 보다 먼저 열린다.
     *
     * 둘 중 하나라도 생기면 그 전제가 깨진다 —
     * ① `JpaPremiumRepositoryAdapter` 가 cache miss 에 write-back 을 하게 되거나,
     * ② `apps:api` 에 premium 캐시를 쓰는 경로가 생기거나.
     * 그러면 이 테스트 안의 `createDraftPlan()`(→ `prepare` → `findLatestSnapshot`)이 캐시를
     * 데우고, 뒤이은 `recordFromMarket` 은 DB 를 치지 않아 `INSERT` 앞에 consistent read 가
     * 사라진다. **교차 ③이 교차 ②로 퇴화하면서 통과한다 — 통과하지만 아무것도 재지 않는다.**
     *
     * setUp 의 `flushAll` 은 이것을 막지 못한다. 그건 테스트 **시작 전**을 비울 뿐이고 캐시를
     * 데우는 `createDraftPlan()` 은 그 뒤에 실행되기 때문이다.
     *
     * 판별력이 살아 있는지 확인하는 방법: `TrackingFacade.recordFromMarket` 의 `lockOwner` 를
     * 지우면 이 테스트가 timeout probe 가 아니라 [assertNoCoexistence] 에서 실패해야 한다.
     */
    @Test
    fun `recordFromMarket 교차에서도 tracking 생성이 직렬화돼 활성 계획을 무효화한다`() {
        val planId = createDraftPlan()
        gate.arm()

        val registerFuture = pool.submit(Callable { registerTarget(planId) })
        check(gate.awaitEntered(AWAIT_SECONDS)) { "registerTarget did not reach the gate" }

        val trackingFuture = pool.submit(Callable { recordTrackingFromMarket() })

        assertThatThrownBy { trackingFuture.get(BLOCKED_PROBE_SECONDS, TimeUnit.SECONDS) }
            .isInstanceOf(TimeoutException::class.java)

        gate.release()

        val registerResponse = registerFuture.get(AWAIT_SECONDS, TimeUnit.SECONDS)
        val trackingResponse = trackingFuture.get(AWAIT_SECONDS, TimeUnit.SECONDS)

        assertThat(registerResponse.status).isEqualTo(200)
        assertThat(trackingResponse.status).isEqualTo(201)
        assertNoCoexistence(planId, expectedPlanStatus = TradePreparationStatus.INVALIDATED)
    }

    /** `ACTIVE` tracking 과 활성(`WATCHING`·`ARMED`) 계획은 어떤 교차에서도 공존하지 않는다. */
    private fun assertNoCoexistence(planId: Long, expectedPlanStatus: TradePreparationStatus) {
        assertThat(trackingRepository.countActiveByMemberId(memberId)).isEqualTo(1L)
        assertThat(tradePreparationRepository.findActiveByOwnerId(memberId)).isNull()
        assertThat(tradePreparationRepository.findById(planId)!!.status).isEqualTo(expectedPlanStatus)
    }

    // ── HTTP 호출 ──────────────────────────────────────────────────────────

    private fun prepare(): MockHttpServletResponse = mockMvc.post("/api/v1/trade-preparations") {
        header("Authorization", "Bearer $token")
        contentType = MediaType.APPLICATION_JSON
        content = objectMapper.writeValueAsString(
            mapOf(
                "symbol" to SYMBOL,
                "koreaExchange" to Exchange.BITHUMB.name,
                "foreignExchange" to Exchange.BINANCE.name,
                "koreaBalance" to BigDecimal("5000000"),
                "foreignBalance" to BigDecimal("1000"),
            ),
        )
    }.andReturn().response

    private fun registerTarget(planId: Long): MockHttpServletResponse =
        mockMvc.post("/api/v1/trade-preparations/$planId/target") {
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(mapOf("desiredEntryPremiumRate" to "0.50"))
        }.andReturn().response

    private fun recordTracking(): MockHttpServletResponse = mockMvc.post("/api/v1/trackings") {
        header("Authorization", "Bearer $token")
        contentType = MediaType.APPLICATION_JSON
        content = objectMapper.writeValueAsString(
            mapOf(
                "symbol" to SYMBOL,
                "koreaExchange" to Exchange.BITHUMB.name,
                "koreaQuantity" to BigDecimal("0.038"),
                "koreaEntryPrice" to KOREA_PRICE,
                "foreignExchange" to Exchange.BINANCE.name,
                "foreignQuantity" to BigDecimal("0.038"),
                "foreignEntryPrice" to FOREIGN_PRICE,
                "foreignLeverage" to 3,
                "entryFxRate" to FX_RATE,
                "entryObservedAt" to "2026-08-30T00:00:00Z",
            ),
        )
    }.andReturn().response

    /** production 경로. 진입가·환율을 요청이 아니라 현재 premium snapshot 에서 읽는다. */
    private fun recordTrackingFromMarket(): MockHttpServletResponse =
        mockMvc.post("/api/v1/trackings/from-market") {
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(
                mapOf(
                    "symbol" to SYMBOL,
                    "koreaExchange" to Exchange.BITHUMB.name,
                    "koreaQuantity" to BigDecimal("0.038"),
                    "foreignExchange" to Exchange.BINANCE.name,
                    "foreignQuantity" to BigDecimal("0.038"),
                    "foreignLeverage" to 3,
                ),
            )
        }.andReturn().response

    private fun countPlans(): Long = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM trade_preparation WHERE owner_id = ?",
        Long::class.java,
        memberId,
    )!!

    private fun createDraftPlan(): Long {
        val response = prepare()
        check(response.status == 201) { "prepare failed: ${response.status} ${response.contentAsString}" }
        return objectMapper.readTree(response.contentAsString).get("planId").asLong()
    }

    private fun login(): String {
        val body = mockMvc.post("/api/v1/members/login") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(mapOf("email" to EMAIL, "password" to PASSWORD))
        }.andReturn().response.contentAsString
        return objectMapper.readTree(body).get("accessToken").asText()
    }

    private fun errorCode(response: MockHttpServletResponse): String =
        objectMapper.readTree(response.contentAsString).get("code").asText()

    // ── fixture ────────────────────────────────────────────────────────────

    private fun saveActiveTracking(): Tracking = trackingRepository.save(activeTracking())

    private fun activeTracking(): Tracking = Tracking.create(
        TrackingRecordSpec(
            memberId = memberId,
            pair = MarketPair(Symbol(SYMBOL), Exchange.BITHUMB, Exchange.BINANCE),
            koreaQuantity = BigDecimal("0.038"),
            koreaEntryPrice = KOREA_PRICE,
            foreignQuantity = BigDecimal("0.038"),
            foreignEntryPrice = FOREIGN_PRICE,
            foreignLeverage = 3,
            entryFxRate = FX_RATE,
            entryObservedAt = Instant.parse("2026-08-30T00:00:00Z"),
        ),
    )

    /**
     * 여기서만 벽시계를 읽는다. `recordFromMarket` 이 snapshot `observedAt` 을 애플리케이션의 실제
     * clock 과 60초 창으로 비교하므로(`TrackingFacade.isFresh`), 고정 시각을 쓰면 신선도에서
     * 거절되어 교차 ③이 성립하지 않는다. 이 값은 어떤 단언의 대상도 아니다.
     */
    private fun savePremium(): Premium {
        val observedAt = Instant.now()
        val korea = tickerRepository.save(
            Ticker.create(Exchange.BITHUMB, Quote.coin(Symbol(SYMBOL), Currency.KRW), KOREA_PRICE, observedAt),
        )
        val foreign = tickerRepository.save(
            Ticker.create(Exchange.BINANCE, Quote.coin(Symbol(SYMBOL), Currency.USD), FOREIGN_PRICE, observedAt),
        )
        val fx = tickerRepository.save(
            Ticker.create(Exchange.FX_PROVIDER, Quote.fx(Currency.USD, Currency.KRW), FX_RATE, observedAt),
        )
        return premiumRepository.save(Premium.create(korea, foreign, fx))
    }

    @TestConfiguration
    class GatedBalanceConfig {
        @Bean
        fun gatedVerifiedBalanceReadPort(): GatedVerifiedBalanceReadPort = GatedVerifiedBalanceReadPort()
    }

    companion object {
        private const val EMAIL = "trade-prep-active-tracking@example.com"
        private const val PASSWORD = "password123"
        private const val SYMBOL = "BTC"
        private const val AWAIT_SECONDS = 20L

        /**
         * "막혀 있다"를 확인하는 관측 창이다. 잠금이 동작하면 이 시간 안에 끝날 수 **없고**(빗장을
         * 풀기 전까지 영원히 막힌다), 동작하지 않으면 수십 ms 만에 끝난다 — 두 경우의 간격이
         * 커서 창 길이에 판정이 좌우되지 않는다.
         */
        private const val BLOCKED_PROBE_SECONDS = 3L

        private val KOREA_PRICE = BigDecimal("129555000")
        private val FOREIGN_PRICE = BigDecimal("89500")
        private val FX_RATE = BigDecimal("1432.6")

        @Container
        @JvmStatic
        val mysql: MySQLContainer<*> = MySQLContainer("mysql:8.0")
            .withDatabaseName("premiumspread_trade_prep_active_tracking")
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
            // V16 의 generated column·unique index 를 보존하려면 Flyway 스키마를 Hibernate 가
            // 지우면 안 된다 (TradePreparationConcurrencyIntegrationTest 와 같은 이유).
            registry.add("spring.jpa.hibernate.ddl-auto") { "validate" }
            // 기본 허가 목록은 비어 있다 (D10 · AC12). 이 계약의 회원이 계획을 만들어야 교차를
            // 재현할 수 있으므로 그 회원만 owner 로 넣는다.
            registry.add("trade-preparation.owner.allowed-emails") { EMAIL }
        }
    }
}

/**
 * `registerTarget` 을 자기 트랜잭션 안에서 멈춰 세우는 판정용 잔고 원천이다 (테스트 전용).
 *
 * 빗장이 걸려 있지 않으면 그냥 `FRESH` 를 돌려준다. `resolveBinding` 은 member 잠금과 `ACTIVE`
 * 재검사 뒤에 딱 한 번 불리므로, 여기서 멈추면 "member 를 잠근 채 미커밋" 상태가 된다.
 */
class GatedVerifiedBalanceReadPort : VerifiedBalanceReadPort {

    @Volatile
    private var entered: CountDownLatch? = null

    @Volatile
    private var proceed: CountDownLatch? = null

    fun arm() {
        entered = CountDownLatch(1)
        proceed = CountDownLatch(1)
    }

    fun disarm() {
        release()
        entered = null
        proceed = null
    }

    fun awaitEntered(seconds: Long): Boolean = entered?.await(seconds, TimeUnit.SECONDS) ?: true

    fun release() {
        proceed?.countDown()
    }

    override fun findForDecision(): VerifiedBalance? {
        entered?.countDown()
        proceed?.let { check(it.await(30, TimeUnit.SECONDS)) { "gate was never released" } }
        return VerifiedBalance.from(
            BalanceSnapshot(
                id = "recorded-gated-1",
                koreaBalance = BigDecimal("5000000"),
                foreignBalance = BigDecimal("1000"),
                balanceBasis = BalanceBasis.FRESH,
                observedAt = Instant.parse("2026-08-30T00:00:00Z"),
            ),
        )
    }
}
