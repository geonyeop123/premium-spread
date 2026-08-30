package io.premiumspread.interfaces.api.tradeprep

import com.fasterxml.jackson.databind.ObjectMapper
import io.premiumspread.config.TestConfig
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
import io.premiumspread.domain.tradeprep.TradePreparationRepository
import io.premiumspread.testcontainers.MySqlTestContainersConfig
import io.premiumspread.testcontainers.RedisTestContainersConfig
import io.premiumspread.utils.DatabaseCleanUp
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.MediaType
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import java.math.BigDecimal
import java.time.Instant

/**
 * 거래 준비 계약 테스트의 공통 설치. 각 계약(AC1·AC9·AC12·AC19)은 하위 클래스가 소유한다.
 *
 * AC16(`TradePreparationActiveTrackingContractTest`)은 이 base 를 쓰지 않는다 — V16 의
 * `active_key` 와 unique index 를 보존하려면 `ddl-auto: validate` 인 전용 container 가 필요해
 * context 설정 자체가 다르다.
 */
@Tag("integration")
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(MySqlTestContainersConfig::class, RedisTestContainersConfig::class, TestConfig::class)
abstract class TradePreparationContractTestBase {

    @Autowired
    protected lateinit var mockMvc: MockMvc

    @Autowired
    protected lateinit var objectMapper: ObjectMapper

    @Autowired
    protected lateinit var databaseCleanUp: DatabaseCleanUp

    @Autowired
    protected lateinit var redisTemplate: StringRedisTemplate

    @Autowired
    protected lateinit var memberRepository: MemberRepository

    @Autowired
    protected lateinit var passwordEncoder: PasswordEncoder

    @Autowired
    protected lateinit var tickerRepository: TickerRepository

    @Autowired
    protected lateinit var premiumRepository: PremiumRepository

    @Autowired
    protected lateinit var tradePreparationRepository: TradePreparationRepository

    protected var memberId: Long = 0L
    protected lateinit var token: String

    @BeforeEach
    fun installBase() {
        databaseCleanUp.truncateAllTables()
        redisTemplate.execute { it.serverCommands().flushAll() }
        memberId = newMember(OWNER_EMAIL)
        token = login(OWNER_EMAIL)
        savePremium()
    }

    protected fun newMember(email: String): Long =
        memberRepository.save(
            Member.create(email = email, encodedPassword = passwordEncoder.encode(PASSWORD)),
        ).id

    protected fun login(email: String): String {
        val body = mockMvc.post("/api/v1/members/login") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(mapOf("email" to email, "password" to PASSWORD))
        }.andReturn().response.contentAsString
        return objectMapper.readTree(body).get("accessToken").asText()
    }

    /**
     * 기준 프리미엄 스냅샷을 심는다. 기본값은 `1.04%` 프리미엄이며 [PLANNABLE_KOREA_BALANCE] 와
     * 짝지으면 캡 안쪽, [CAP_VIOLATING_KOREA_BALANCE] 와 짝지으면 `EFFICIENCY_CAP` 하나만
     * 위반한다 — 두 경로를 값 하나로 갈라 다른 변수를 고정한다.
     */
    protected fun savePremium(observedAt: Instant = Instant.now()): Premium {
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

    protected fun prepareBody(
        koreaBalance: BigDecimal = PLANNABLE_KOREA_BALANCE,
        foreignBalance: BigDecimal = FOREIGN_BALANCE,
        extra: Map<String, Any> = emptyMap(),
    ): String = objectMapper.writeValueAsString(
        mapOf(
            "symbol" to SYMBOL,
            "koreaExchange" to Exchange.BITHUMB.name,
            "foreignExchange" to Exchange.BINANCE.name,
            "koreaBalance" to koreaBalance,
            "foreignBalance" to foreignBalance,
        ) + extra,
    )

    /** `DRAFT` 계획을 실제 API 로 만들고 id 를 돌려준다. */
    protected fun createDraftPlan(accessToken: String = token): Long {
        val body = mockMvc.post("/api/v1/trade-preparations") {
            header("Authorization", "Bearer $accessToken")
            contentType = MediaType.APPLICATION_JSON
            content = prepareBody()
        }.andReturn().response.contentAsString
        return objectMapper.readTree(body).get("planId").asLong()
    }

    companion object {
        const val OWNER_EMAIL = "trade-prep-owner@example.com"
        const val OTHER_EMAIL = "trade-prep-other@example.com"
        const val PASSWORD = "password123"
        const val SYMBOL = "BTC"

        val KOREA_PRICE: BigDecimal = BigDecimal("129555000")
        val FOREIGN_PRICE: BigDecimal = BigDecimal("89500")
        val FX_RATE: BigDecimal = BigDecimal("1432.6")

        /** 캡 안쪽. `koreaShare ≈ 0.777 >= 0.60`, `leverage ≈ 3.40 < 7`. */
        val PLANNABLE_KOREA_BALANCE: BigDecimal = BigDecimal("5000000")

        /** `koreaShare ≈ 0.411 < 0.60` 이라 `EFFICIENCY_CAP` 하나만 위반한다. */
        val CAP_VIOLATING_KOREA_BALANCE: BigDecimal = BigDecimal("1000000")

        val FOREIGN_BALANCE: BigDecimal = BigDecimal("1000")
    }
}
