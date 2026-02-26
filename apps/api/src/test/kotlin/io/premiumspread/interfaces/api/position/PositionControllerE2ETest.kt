package io.premiumspread.interfaces.api.position

import com.fasterxml.jackson.databind.ObjectMapper
import io.premiumspread.config.TestConfig
import io.premiumspread.domain.member.Member
import io.premiumspread.domain.member.MemberRepository
import io.premiumspread.domain.position.Position
import io.premiumspread.domain.position.PositionRepository
import io.premiumspread.domain.position.PositionStatus
import io.premiumspread.domain.premium.Premium
import io.premiumspread.domain.premium.PremiumRepository
import io.premiumspread.domain.ticker.Currency
import io.premiumspread.domain.ticker.Exchange
import io.premiumspread.domain.ticker.Quote
import io.premiumspread.domain.ticker.Symbol
import io.premiumspread.domain.ticker.Ticker
import io.premiumspread.domain.ticker.TickerRepository
import io.premiumspread.redis.RedisKeyGenerator
import io.premiumspread.testcontainers.MySqlTestContainersConfig
import io.premiumspread.testcontainers.RedisTestContainersConfig
import io.premiumspread.utils.DatabaseCleanUp
import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.MediaType
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.math.BigDecimal
import java.time.Instant

@Tag("integration")
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(MySqlTestContainersConfig::class, RedisTestContainersConfig::class, TestConfig::class)
class PositionControllerE2ETest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val objectMapper: ObjectMapper,
    private val databaseCleanUp: DatabaseCleanUp,
    private val redisTemplate: StringRedisTemplate,
    private val positionRepository: PositionRepository,
    private val memberRepository: MemberRepository,
    private val passwordEncoder: PasswordEncoder,
    private val tickerRepository: TickerRepository,
    private val premiumRepository: PremiumRepository,
) {

    private var memberId: Long = 0L

    @BeforeEach
    fun setUp() {
        databaseCleanUp.truncateAllTables()
        redisTemplate.execute { it.serverCommands().flushAll() }
        val member = memberRepository.save(
            Member.create(
                email = "test@example.com",
                encodedPassword = passwordEncoder.encode("password123"),
            ),
        )
        memberId = member.id
    }

    // -- 인증 헬퍼 --

    private fun login(email: String = "test@example.com", password: String = "password123"): MvcResult {
        val request = mapOf("email" to email, "password" to password)
        return mockMvc.post("/api/v1/members/login") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(request)
        }.andReturn()
    }

    private fun sessionCookie(result: MvcResult): Cookie? {
        return result.response.cookies.firstOrNull { it.name == "SESSION" }
    }

    // -- 데이터 준비 헬퍼 --

    private fun createPosition(
        symbol: String = "BTC",
        exchange: Exchange = Exchange.UPBIT,
        entryPremiumRate: BigDecimal = BigDecimal("1.28"),
    ): Position = positionRepository.save(
        Position.create(
            memberId = memberId,
            symbol = Symbol(symbol),
            exchange = exchange,
            quantity = BigDecimal("0.5"),
            entryPrice = BigDecimal("129555000"),
            entryFxRate = BigDecimal("1432.6"),
            entryPremiumRate = entryPremiumRate,
            entryObservedAt = Instant.parse("2024-01-01T00:00:00Z"),
        ),
    )

    private fun savePremiumWithTickers(
        symbol: String = "BTC",
        koreaPrice: BigDecimal = BigDecimal("129555000"),
        foreignPrice: BigDecimal = BigDecimal("89277"),
        fxRate: BigDecimal = BigDecimal("1432.6"),
        observedAt: Instant = Instant.parse("2024-01-01T00:00:00Z"),
    ): Premium {
        val koreaTicker = tickerRepository.save(
            Ticker.create(
                exchange = Exchange.UPBIT,
                quote = Quote.coin(Symbol(symbol), Currency.KRW),
                price = koreaPrice,
                observedAt = observedAt,
            ),
        )
        val foreignTicker = tickerRepository.save(
            Ticker.create(
                exchange = Exchange.BINANCE,
                quote = Quote.coin(Symbol(symbol), Currency.USD),
                price = foreignPrice,
                observedAt = observedAt,
            ),
        )
        val fxTicker = tickerRepository.save(
            Ticker.create(
                exchange = Exchange.FX_PROVIDER,
                quote = Quote.fx(Currency.USD, Currency.KRW),
                price = fxRate,
                observedAt = observedAt,
            ),
        )
        return premiumRepository.save(Premium.create(koreaTicker, foreignTicker, fxTicker))
    }

    // -- 인증 필수 확인 --

    @Test
    fun `인증 없이 포지션 목록 조회 시 401 반환`() {
        mockMvc.get("/api/v1/positions")
            .andExpect { status { isUnauthorized() } }
    }

    // -- POST /api/v1/positions --

    @Test
    fun `포지션 오픈 성공 - DB 저장 + Redis 캐시 갱신`() {
        // given
        val loginResult = login()
        val cookie = sessionCookie(loginResult)!!

        val request = mapOf(
            "symbol" to "BTC",
            "exchange" to "UPBIT",
            "quantity" to "0.5",
            "entryPrice" to "129555000",
            "entryFxRate" to "1432.6",
            "entryPremiumRate" to "1.28",
            "entryObservedAt" to "2024-01-01T00:00:00Z",
        )

        // when & then
        mockMvc.post("/api/v1/positions") {
            cookie(cookie)
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(request)
        }.andExpect {
            status { isCreated() }
            jsonPath("$.id") { isNumber() }
            jsonPath("$.symbol") { value("BTC") }
            jsonPath("$.status") { value("OPEN") }
        }

        // Redis 캐시 갱신 확인
        val exists = redisTemplate.opsForValue().get(RedisKeyGenerator.positionOpenExistsKey())
        val count = redisTemplate.opsForValue().get(RedisKeyGenerator.positionOpenCountKey())
        assertThat(exists).isEqualTo("true")
        assertThat(count).isEqualTo("1")
    }

    @Test
    fun `잘못된 거래소로 오픈 시 400 반환`() {
        // given
        val loginResult = login()
        val cookie = sessionCookie(loginResult)!!

        val request = mapOf(
            "symbol" to "BTC",
            "exchange" to "INVALID_EXCHANGE",
            "quantity" to "0.5",
            "entryPrice" to "129555000",
            "entryFxRate" to "1432.6",
            "entryPremiumRate" to "1.28",
            "entryObservedAt" to "2024-01-01T00:00:00Z",
        )

        // when & then
        mockMvc.post("/api/v1/positions") {
            cookie(cookie)
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(request)
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_ARGUMENT") }
        }
    }

    // -- GET /api/v1/positions/{id} --

    @Test
    fun `존재하는 포지션 단건 조회 성공`() {
        // given
        val saved = createPosition()
        val loginResult = login()
        val cookie = sessionCookie(loginResult)!!

        // when & then
        mockMvc.get("/api/v1/positions/${saved.id}") {
            cookie(cookie)
        }.andExpect {
            status { isOk() }
            jsonPath("$.id") { value(saved.id) }
            jsonPath("$.symbol") { value("BTC") }
            jsonPath("$.status") { value("OPEN") }
        }
    }

    @Test
    fun `없는 포지션 조회 시 404 반환`() {
        // given
        val loginResult = login()
        val cookie = sessionCookie(loginResult)!!

        // when & then
        mockMvc.get("/api/v1/positions/999") {
            cookie(cookie)
        }.andExpect {
            status { isNotFound() }
        }
    }

    // -- GET /api/v1/positions --

    @Test
    fun `OPEN 포지션만 필터링하여 반환`() {
        // given: OPEN 2건, CLOSED 1건
        createPosition(symbol = "BTC")
        createPosition(symbol = "ETH")
        val closedPosition = createPosition(symbol = "SOL")
        closedPosition.close()
        positionRepository.save(closedPosition)
        val loginResult = login()
        val cookie = sessionCookie(loginResult)!!

        // when & then
        mockMvc.get("/api/v1/positions") {
            cookie(cookie)
        }.andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(2) }
            jsonPath("$[*].status") {
                value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.equalTo("OPEN")))
            }
        }
    }

    @Test
    fun `열린 포지션 없으면 빈 배열 반환`() {
        // given
        val loginResult = login()
        val cookie = sessionCookie(loginResult)!!

        // when & then
        mockMvc.get("/api/v1/positions") {
            cookie(cookie)
        }.andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(0) }
        }
    }

    // -- GET /api/v1/positions/{id}/pnl --

    @Test
    fun `PnL 계산 성공 - DB premium 기준으로 계산 (entryRate=3, currentRate=1 → diff=-2, isProfit=true)`() {
        // given: OPEN 포지션 (entryPremiumRate=3.00)
        val position = createPosition(entryPremiumRate = BigDecimal("3.00"))
        val loginResult = login()
        val cookie = sessionCookie(loginResult)!!

        // DB premium 저장: koreaPrice=101000, foreignPrice=1000, fxRate=100 → premiumRate=1.00
        savePremiumWithTickers(
            koreaPrice = BigDecimal("101000"),
            foreignPrice = BigDecimal("1000"),
            fxRate = BigDecimal("100"),
        )

        // when & then
        mockMvc.get("/api/v1/positions/${position.id}/pnl") {
            cookie(cookie)
        }.andExpect {
            status { isOk() }
            jsonPath("$.positionId") { value(position.id) }
            jsonPath("$.entryPremiumRate") { value(3.00) }
            jsonPath("$.currentPremiumRate") { value(1.00) }
            jsonPath("$.premiumDiff") { value(-2.00) }
            jsonPath("$.isProfit") { value(true) }
        }
    }

    @Test
    fun `DB fallback으로 PnL 계산 - Redis 비어 있을 때 DB premium 사용`() {
        // given: OPEN 포지션 + DB premium
        val position = createPosition(entryPremiumRate = BigDecimal("1.00"))
        val premium = savePremiumWithTickers()
        val loginResult = login()
        val cookie = sessionCookie(loginResult)!!

        // when & then
        mockMvc.get("/api/v1/positions/${position.id}/pnl") {
            cookie(cookie)
        }.andExpect {
            status { isOk() }
            jsonPath("$.positionId") { value(position.id) }
            jsonPath("$.currentPremiumRate") { value(premium.premiumRate.toDouble()) }
        }
    }

    @Test
    fun `프리미엄 없으면 404 반환`() {
        // given: OPEN 포지션, Redis 비어 있음, DB premium 없음
        val position = createPosition()
        val loginResult = login()
        val cookie = sessionCookie(loginResult)!!

        // when & then
        mockMvc.get("/api/v1/positions/${position.id}/pnl") {
            cookie(cookie)
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("PREMIUM_NOT_FOUND") }
        }
    }

    // -- POST /api/v1/positions/{id}/close --

    @Test
    fun `포지션 청산 성공 - DB CLOSED 상태 + Redis 캐시 갱신`() {
        // given: OPEN 포지션 1건
        val position = createPosition()
        val loginResult = login()
        val cookie = sessionCookie(loginResult)!!
        // Redis 초기 상태 설정
        redisTemplate.opsForValue().set(RedisKeyGenerator.positionOpenExistsKey(), "true")
        redisTemplate.opsForValue().set(RedisKeyGenerator.positionOpenCountKey(), "1")

        // when & then
        mockMvc.post("/api/v1/positions/${position.id}/close") {
            cookie(cookie)
        }.andExpect {
            status { isOk() }
            jsonPath("$.id") { value(position.id) }
            jsonPath("$.status") { value("CLOSED") }
        }

        // DB 상태 변경 확인
        val updated = positionRepository.findById(position.id)
        assertThat(updated?.status).isEqualTo(PositionStatus.CLOSED)

        // Redis 캐시 갱신 확인 (OPEN 포지션이 0건이므로 exists=false, count=0)
        val exists = redisTemplate.opsForValue().get(RedisKeyGenerator.positionOpenExistsKey())
        val count = redisTemplate.opsForValue().get(RedisKeyGenerator.positionOpenCountKey())
        assertThat(exists).isEqualTo("false")
        assertThat(count).isEqualTo("0")
    }

    @Test
    fun `없는 포지션 청산 시 404 반환`() {
        // given
        val loginResult = login()
        val cookie = sessionCookie(loginResult)!!

        // when & then
        mockMvc.post("/api/v1/positions/999/close") {
            cookie(cookie)
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("POSITION_NOT_FOUND") }
        }
    }
}
