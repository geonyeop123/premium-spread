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

    @Test
    fun `인증 없이 포지션 목록 조회 시 401 반환`() {
        mockMvc.get("/api/v1/positions")
            .andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `AUTO 포지션 오픈 성공 - 최신 스냅샷으로 DB 저장과 Redis 캐시 갱신`() {
        val accessToken = login()
        val observedAt = Instant.now()
        savePremiumWithTickers(observedAt = observedAt)

        mockMvc.post("/api/v1/positions/auto") {
            header("Authorization", "Bearer $accessToken")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(openAutoRequest())
        }.andExpect {
            status { isCreated() }
            jsonPath("$.id") { isNumber() }
            jsonPath("$.symbol") { value("BTC") }
            jsonPath("$.koreaExchange") { value("BITHUMB") }
            jsonPath("$.koreaEntryPrice") { value(129555000) }
            jsonPath("$.foreignExchange") { value("BINANCE") }
            jsonPath("$.foreignEntryPrice") { value(89500) }
            jsonPath("$.entryFxRate") { value(1432.6) }
            jsonPath("$.status") { value("OPEN") }
        }

        val exists = redisTemplate.opsForValue().get(RedisKeyGenerator.positionOpenExistsKey())
        val count = redisTemplate.opsForValue().get(RedisKeyGenerator.positionOpenCountKey())
        assertThat(exists).isEqualTo("true")
        assertThat(count).isEqualTo("1")
    }

    @Test
    fun `AUTO 포지션 오픈 시 스냅샷이 없으면 409 반환`() {
        val accessToken = login()

        mockMvc.post("/api/v1/positions/auto") {
            header("Authorization", "Bearer $accessToken")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(openAutoRequest(symbol = "DOGE"))
        }.andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("PREMIUM_SNAPSHOT_NOT_AVAILABLE") }
        }
    }

    @Test
    fun `AUTO 포지션 오픈 시 스냅샷이 오래되면 409 반환`() {
        val accessToken = login()
        savePremiumWithTickers(symbol = "ETH", observedAt = Instant.now().minusSeconds(120))

        mockMvc.post("/api/v1/positions/auto") {
            header("Authorization", "Bearer $accessToken")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(openAutoRequest(symbol = "ETH"))
        }.andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("STALE_PREMIUM_SNAPSHOT") }
        }
    }

    @Test
    fun `AUTO 포지션 오픈 시 region 위반이면 400 반환`() {
        val accessToken = login()
        savePremiumWithTickers(observedAt = Instant.now())

        mockMvc.post("/api/v1/positions/auto") {
            header("Authorization", "Bearer $accessToken")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(openAutoRequest(koreaExchange = "BINANCE"))
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_POSITION") }
        }
    }

    @Test
    fun `MANUAL 포지션 오픈 성공 - 입력값으로 DB 저장과 Redis 캐시 갱신`() {
        val accessToken = login()

        mockMvc.post("/api/v1/positions/manual") {
            header("Authorization", "Bearer $accessToken")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(openManualRequest())
        }.andExpect {
            status { isCreated() }
            jsonPath("$.id") { isNumber() }
            jsonPath("$.symbol") { value("BTC") }
            jsonPath("$.koreaExchange") { value("BITHUMB") }
            jsonPath("$.koreaEntryPrice") { value(161493792) }
            jsonPath("$.foreignExchange") { value("BINANCE") }
            jsonPath("$.foreignEntryPrice") { value(118100) }
            jsonPath("$.entryFxRate") { value(1521.6) }
            jsonPath("$.status") { value("OPEN") }
        }

        val exists = redisTemplate.opsForValue().get(RedisKeyGenerator.positionOpenExistsKey())
        val count = redisTemplate.opsForValue().get(RedisKeyGenerator.positionOpenCountKey())
        assertThat(exists).isEqualTo("true")
        assertThat(count).isEqualTo("1")
    }

    @Test
    fun `MANUAL 포지션 오픈 시 region 위반이면 400 반환`() {
        val accessToken = login()

        mockMvc.post("/api/v1/positions/manual") {
            header("Authorization", "Bearer $accessToken")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(openManualRequest(koreaExchange = "BINANCE"))
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_POSITION") }
        }
    }

    @Test
    fun `루트 POST 포지션 생성 라우트는 제거되어 있다`() {
        val accessToken = login()

        mockMvc.post("/api/v1/positions") {
            header("Authorization", "Bearer $accessToken")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(openManualRequest())
        }.andExpect {
            status { isMethodNotAllowed() }
        }
    }

    @Test
    fun `존재하는 포지션 단건 조회 성공`() {
        val saved = createPosition()
        val accessToken = login()

        mockMvc.get("/api/v1/positions/${saved.id}") {
            header("Authorization", "Bearer $accessToken")
        }.andExpect {
            status { isOk() }
            jsonPath("$.id") { value(saved.id) }
            jsonPath("$.symbol") { value("BTC") }
            jsonPath("$.status") { value("OPEN") }
        }
    }

    @Test
    fun `없는 포지션 조회 시 404 반환`() {
        val accessToken = login()

        mockMvc.get("/api/v1/positions/999") {
            header("Authorization", "Bearer $accessToken")
        }.andExpect {
            status { isNotFound() }
        }
    }

    @Test
    fun `OPEN 포지션만 필터링하여 반환`() {
        createPosition(symbol = "BTC")
        createPosition(symbol = "ETH")
        val closedPosition = createPosition(symbol = "SOL")
        closedPosition.close()
        positionRepository.save(closedPosition)
        val accessToken = login()

        mockMvc.get("/api/v1/positions") {
            header("Authorization", "Bearer $accessToken")
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
        val accessToken = login()

        mockMvc.get("/api/v1/positions") {
            header("Authorization", "Bearer $accessToken")
        }.andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(0) }
        }
    }

    @Test
    fun `PnL 계산 성공 - DB premium 기준으로 계산 (entryRate=3, currentRate=1 → diff=-2, isProfit=true)`() {
        val position = createPosition(
            koreaEntryPrice = BigDecimal("103000"),
            foreignEntryPrice = BigDecimal("100"),
            entryFxRate = BigDecimal("1000"),
        )
        val accessToken = login()
        savePremiumWithTickers(
            koreaPrice = BigDecimal("101000"),
            foreignPrice = BigDecimal("1000"),
            fxRate = BigDecimal("100"),
        )

        mockMvc.get("/api/v1/positions/${position.id}/pnl") {
            header("Authorization", "Bearer $accessToken")
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
        val position = createPosition(
            koreaEntryPrice = BigDecimal("101000"),
            foreignEntryPrice = BigDecimal("100"),
            entryFxRate = BigDecimal("1000"),
        )
        val premium = savePremiumWithTickers()
        val accessToken = login()

        mockMvc.get("/api/v1/positions/${position.id}/pnl") {
            header("Authorization", "Bearer $accessToken")
        }.andExpect {
            status { isOk() }
            jsonPath("$.positionId") { value(position.id) }
            jsonPath("$.currentPremiumRate") { value(premium.premiumRate.toDouble()) }
        }
    }

    @Test
    fun `프리미엄 없으면 404 반환`() {
        val position = createPosition()
        val accessToken = login()

        mockMvc.get("/api/v1/positions/${position.id}/pnl") {
            header("Authorization", "Bearer $accessToken")
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("PREMIUM_NOT_FOUND") }
        }
    }

    @Test
    fun `포지션 청산 성공 - DB CLOSED 상태 + Redis 캐시 갱신`() {
        val position = createPosition()
        val accessToken = login()
        redisTemplate.opsForValue().set(RedisKeyGenerator.positionOpenExistsKey(), "true")
        redisTemplate.opsForValue().set(RedisKeyGenerator.positionOpenCountKey(), "1")

        mockMvc.post("/api/v1/positions/${position.id}/close") {
            header("Authorization", "Bearer $accessToken")
        }.andExpect {
            status { isOk() }
            jsonPath("$.id") { value(position.id) }
            jsonPath("$.status") { value("CLOSED") }
        }

        val updated = positionRepository.findById(position.id)
        assertThat(updated?.status).isEqualTo(PositionStatus.CLOSED)

        val exists = redisTemplate.opsForValue().get(RedisKeyGenerator.positionOpenExistsKey())
        val count = redisTemplate.opsForValue().get(RedisKeyGenerator.positionOpenCountKey())
        assertThat(exists).isEqualTo("false")
        assertThat(count).isEqualTo("0")
    }

    @Test
    fun `없는 포지션 청산 시 404 반환`() {
        val accessToken = login()

        mockMvc.post("/api/v1/positions/999/close") {
            header("Authorization", "Bearer $accessToken")
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("POSITION_NOT_FOUND") }
        }
    }

    private fun login(email: String = "test@example.com", password: String = "password123"): String {
        val request = mapOf("email" to email, "password" to password)
        val result = mockMvc.post("/api/v1/members/login") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(request)
        }.andReturn()
        val body = objectMapper.readTree(result.response.contentAsString)
        return body["accessToken"].asText()
    }

    private fun createPosition(
        symbol: String = "BTC",
        koreaEntryPrice: BigDecimal = BigDecimal("129555000"),
        foreignEntryPrice: BigDecimal = BigDecimal("89500"),
        entryFxRate: BigDecimal = BigDecimal("1432.6"),
    ): Position = positionRepository.save(
        Position.create(
            memberId = memberId,
            symbol = Symbol(symbol),
            koreaExchange = Exchange.UPBIT,
            koreaQuantity = BigDecimal("0.5"),
            koreaEntryPrice = koreaEntryPrice,
            foreignExchange = Exchange.BINANCE,
            foreignQuantity = BigDecimal("0.5"),
            foreignEntryPrice = foreignEntryPrice,
            foreignLeverage = 1,
            entryFxRate = entryFxRate,
            entryObservedAt = Instant.parse("2024-01-01T00:00:00Z"),
        ),
    )

    private fun savePremiumWithTickers(
        symbol: String = "BTC",
        koreaPrice: BigDecimal = BigDecimal("129555000"),
        foreignPrice: BigDecimal = BigDecimal("89500"),
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

    private fun openAutoRequest(
        symbol: String = "BTC",
        koreaExchange: String = "BITHUMB",
    ): Map<String, Any> = mapOf(
        "symbol" to symbol,
        "koreaExchange" to koreaExchange,
        "koreaQuantity" to "0.157",
        "foreignExchange" to "BINANCE",
        "foreignQuantity" to "0.15",
        "foreignLeverage" to 5,
    )

    private fun openManualRequest(
        koreaExchange: String = "BITHUMB",
    ): Map<String, Any> = mapOf(
        "symbol" to "BTC",
        "koreaExchange" to koreaExchange,
        "koreaQuantity" to "0.157",
        "koreaEntryPrice" to "161493792",
        "foreignExchange" to "BINANCE",
        "foreignQuantity" to "0.15",
        "foreignEntryPrice" to "118100",
        "foreignLeverage" to 5,
        "entryFxRate" to "1521.6",
        "entryObservedAt" to "2026-04-01T10:30:00Z",
    )
}
