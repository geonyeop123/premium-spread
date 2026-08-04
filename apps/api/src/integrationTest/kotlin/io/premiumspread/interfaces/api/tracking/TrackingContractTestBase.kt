package io.premiumspread.interfaces.api.tracking

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

/** 추적 기록 계약 테스트의 공통 설치. 각 계약은 하위 클래스가 소유한다. */
@Tag("integration")
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(MySqlTestContainersConfig::class, RedisTestContainersConfig::class, TestConfig::class)
abstract class TrackingContractTestBase {

    @Autowired
    protected lateinit var mockMvc: MockMvc

    @Autowired
    protected lateinit var objectMapper: ObjectMapper

    @Autowired
    protected lateinit var databaseCleanUp: DatabaseCleanUp

    @Autowired
    protected lateinit var redisTemplate: StringRedisTemplate

    @Autowired
    protected lateinit var trackingRepository: TrackingRepository

    @Autowired
    protected lateinit var memberRepository: MemberRepository

    @Autowired
    protected lateinit var passwordEncoder: PasswordEncoder

    @Autowired
    protected lateinit var tickerRepository: TickerRepository

    @Autowired
    protected lateinit var premiumRepository: PremiumRepository

    protected var memberId: Long = 0L
    protected lateinit var token: String

    @BeforeEach
    fun installBase() {
        databaseCleanUp.truncateAllTables()
        redisTemplate.execute { it.serverCommands().flushAll() }
        memberId = memberRepository.save(
            Member.create(email = "owner@example.com", encodedPassword = passwordEncoder.encode("password123")),
        ).id
        token = login("owner@example.com")
    }

    protected fun login(email: String, password: String = "password123"): String {
        val body = mockMvc.post("/api/v1/members/login") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(mapOf("email" to email, "password" to password))
        }.andReturn().response.contentAsString
        return objectMapper.readTree(body).get("accessToken").asText()
    }

    protected fun newMember(email: String): Long =
        memberRepository.save(
            Member.create(email = email, encodedPassword = passwordEncoder.encode("password123")),
        ).id

    protected fun saveTracking(owner: Long = memberId, symbol: String = "BTC"): Tracking =
        trackingRepository.save(
            Tracking.create(
                TrackingRecordSpec(
                    memberId = owner,
                    pair = MarketPair(Symbol(symbol), Exchange.BITHUMB, Exchange.BINANCE),
                    koreaQuantity = BigDecimal("0.5"),
                    koreaEntryPrice = BigDecimal("129555000"),
                    foreignQuantity = BigDecimal("0.5"),
                    foreignEntryPrice = BigDecimal("89500"),
                    foreignLeverage = 1,
                    entryFxRate = BigDecimal("1432.6"),
                    entryObservedAt = Instant.parse("2024-01-01T00:00:00Z"),
                ),
            ),
        )

    /**
     * premium snapshot 을 심는다.
     *
     * [observedAt] 은 세 ticker 의 공통 관측 시각이고 [fxObservedAt] 은 환율만 따로 늦추거나 앞당길 때 쓴다.
     * PremiumSnapshot.observedAt 이 셋의 max 라, FX 만 낡은 상태를 만들려면 FX ticker 시각만 내려야 한다.
     */
    protected fun savePremium(
        symbol: String = "BTC",
        koreaPrice: BigDecimal = BigDecimal("129555000"),
        foreignPrice: BigDecimal = BigDecimal("89500"),
        fxRate: BigDecimal = BigDecimal("1432.6"),
        observedAt: Instant = Instant.now(),
        fxObservedAt: Instant = observedAt,
    ): Premium {
        val korea = tickerRepository.save(
            Ticker.create(Exchange.BITHUMB, Quote.coin(Symbol(symbol), Currency.KRW), koreaPrice, observedAt),
        )
        val foreign = tickerRepository.save(
            Ticker.create(Exchange.BINANCE, Quote.coin(Symbol(symbol), Currency.USD), foreignPrice, observedAt),
        )
        val fx = tickerRepository.save(
            Ticker.create(Exchange.FX_PROVIDER, Quote.fx(Currency.USD, Currency.KRW), fxRate, fxObservedAt),
        )
        return premiumRepository.save(Premium.create(korea, foreign, fx))
    }
}
