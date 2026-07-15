package io.premiumspread.interfaces.api.premium

import com.fasterxml.jackson.databind.ObjectMapper
import io.premiumspread.config.TestConfig
import io.premiumspread.domain.premium.Premium
import io.premiumspread.domain.premium.PremiumRepository
import io.premiumspread.domain.premium.PremiumPolicy
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
import org.springframework.test.context.ActiveProfiles
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
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
class PremiumControllerE2ETest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val objectMapper: ObjectMapper,
    private val databaseCleanUp: DatabaseCleanUp,
    private val redisTemplate: StringRedisTemplate,
    private val tickerRepository: TickerRepository,
    private val premiumRepository: PremiumRepository,
) {

    @BeforeEach
    fun setUp() {
        databaseCleanUp.truncateAllTables()
        redisTemplate.execute { it.serverCommands().flushAll() }
    }

    // -- 데이터 준비 헬퍼 --

    private fun saveKoreaTicker(
        symbol: String = "BTC",
        price: BigDecimal = BigDecimal("129555000"),
        observedAt: Instant = Instant.parse("2024-01-01T00:00:00Z"),
    ): Ticker = tickerRepository.save(
        Ticker.create(
            exchange = Exchange.BITHUMB,
            quote = Quote.coin(Symbol(symbol), Currency.KRW),
            price = price,
            observedAt = observedAt,
        ),
    )

    private fun saveForeignTicker(
        symbol: String = "BTC",
        price: BigDecimal = BigDecimal("89277"),
        observedAt: Instant = Instant.parse("2024-01-01T00:00:00Z"),
    ): Ticker = tickerRepository.save(
        Ticker.create(
            exchange = Exchange.BINANCE,
            quote = Quote.coin(Symbol(symbol), Currency.USD),
            price = price,
            observedAt = observedAt,
        ),
    )

    private fun saveFxTicker(
        price: BigDecimal = BigDecimal("1432.6"),
        observedAt: Instant = Instant.parse("2024-01-01T00:00:00Z"),
    ): Ticker = tickerRepository.save(
        Ticker.create(
            exchange = Exchange.FX_PROVIDER,
            quote = Quote.fx(Currency.USD, Currency.KRW),
            price = price,
            observedAt = observedAt,
        ),
    )

    private fun saveAllTickersAndPremium(
        symbol: String = "BTC",
        koreaPrice: BigDecimal = BigDecimal("129555000"),
        foreignPrice: BigDecimal = BigDecimal("89277"),
        fxRate: BigDecimal = BigDecimal("1432.6"),
        observedAt: Instant = Instant.parse("2024-01-01T00:00:00Z"),
    ): Premium {
        val koreaTicker = saveKoreaTicker(symbol, koreaPrice, observedAt)
        val foreignTicker = saveForeignTicker(symbol, foreignPrice, observedAt)
        val fxTicker = saveFxTicker(fxRate, observedAt)
        return premiumRepository.save(Premium.create(koreaTicker, foreignTicker, fxTicker))
    }

    // -- calculateAndSave --

    @Test
    fun `프리미엄 계산 성공 - DB에 1건 저장되고 premiumRate 일치`() {
        // given
        val koreaPrice = BigDecimal("129555000")
        val foreignPrice = BigDecimal("89277")
        val fxRate = BigDecimal("1432.6")
        saveKoreaTicker(price = koreaPrice)
        saveForeignTicker(price = foreignPrice)
        saveFxTicker(price = fxRate)

        val expectedRate = PremiumPolicy.calculate(koreaPrice, foreignPrice, fxRate).entityPremiumRate

        // when & then
        mockMvc.post("/api/v1/premiums/calculate/BTC") { with(user("internal")) }.andExpect {
            status { isOk() }
            jsonPath("$.id") { isNumber() }
            jsonPath("$.symbol") { value("BTC") }
            jsonPath("$.premiumRate") { value(expectedRate.toDouble()) }
        }
    }

    @Test
    fun `한국 티커 없으면 404 반환`() {
        // given: BINANCE/BTC와 FX 티커만 존재 (UPBIT/BTC 없음)
        saveForeignTicker()
        saveFxTicker()

        // when & then
        mockMvc.post("/api/v1/premiums/calculate/BTC") { with(user("internal")) }.andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("TICKER_NOT_FOUND") }
            jsonPath("$.message") { value("티커를 찾을 수 없습니다.") }
        }
    }

    @Test
    fun `해외 티커 없으면 404 반환`() {
        // given: UPBIT/BTC와 FX 티커만 존재 (BINANCE/BTC 없음)
        saveKoreaTicker()
        saveFxTicker()

        // when & then
        mockMvc.post("/api/v1/premiums/calculate/BTC") { with(user("internal")) }.andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("TICKER_NOT_FOUND") }
            jsonPath("$.message") { value("티커를 찾을 수 없습니다.") }
        }
    }

    @Test
    fun `FX 티커 없으면 404 반환`() {
        // given: UPBIT/BTC와 BINANCE/BTC만 존재 (FX 없음)
        saveKoreaTicker()
        saveForeignTicker()

        // when & then
        mockMvc.post("/api/v1/premiums/calculate/BTC") { with(user("internal")) }.andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("TICKER_NOT_FOUND") }
            jsonPath("$.message") { value("티커를 찾을 수 없습니다.") }
        }
    }

    // -- findLatestSnapshot (캐시 우선 → DB fallback) --

    @Test
    fun `캐시 hit - 4자리 Redis rate를 API 표시 2자리로 정규화한다`() {
        // given: Redis에 premium:btc 해시 저장 (DB에는 데이터 없음)
        val key = RedisKeyGenerator.premiumKey("btc")
        val cacheData = mapOf(
            "symbol" to "BTC",
            "rate" to "1.2350",
            "korea_price" to "129555000",
            "foreign_price" to "89277",
            "foreign_price_krw" to "127884000.2",
            "fx_rate" to "1432.6",
            "observed_at" to Instant.parse("2024-01-01T00:00:00Z").toEpochMilli().toString(),
        )
        redisTemplate.opsForHash<String, String>().putAll(key, cacheData)

        // when & then
        mockMvc.get("/api/v1/premiums/current/BTC").andExpect {
            status { isOk() }
            jsonPath("$.symbol") { value("BTC") }
            jsonPath("$.premiumRate") { value(1.24) }
            jsonPath("$.koreaPrice") { value(129555000) }
        }
    }

    @Test
    fun `캐시 miss + DB hit - DB fallback 반환`() {
        // given: Redis 비어 있음, DB에 premium 1건 준비
        val savedPremium = saveAllTickersAndPremium()

        // when & then
        mockMvc.get("/api/v1/premiums/current/BTC").andExpect {
            status { isOk() }
            jsonPath("$.symbol") { value("BTC") }
            jsonPath("$.premiumRate") { value(savedPremium.premiumRate.toDouble()) }
        }.also {
            // DB premium rate와 동일한지 추가 검증
            assertThat(savedPremium.premiumRate).isNotNull()
        }
    }

    @Test
    fun `캐시 miss + DB miss - 404 반환`() {
        // given: Redis, DB 모두 비어 있음

        // when & then
        mockMvc.get("/api/v1/premiums/current/BTC").andExpect {
            status { isNotFound() }
        }
    }

    // -- findAllBySymbolAndPeriod --

    @Test
    fun `기간 내 데이터 2건 반환 - 기간 밖 1건 제외`() {
        // given
        saveAllTickersAndPremium(observedAt = Instant.parse("2024-01-01T00:00:00Z"))

        // ticker를 다시 저장하여 2번째 premium 생성
        val koreaTicker2 = saveKoreaTicker(observedAt = Instant.parse("2024-01-02T00:00:00Z"))
        val foreignTicker2 = saveForeignTicker(observedAt = Instant.parse("2024-01-02T00:00:00Z"))
        val fxTicker2 = saveFxTicker(observedAt = Instant.parse("2024-01-02T00:00:00Z"))
        premiumRepository.save(Premium.create(koreaTicker2, foreignTicker2, fxTicker2))

        val koreaTicker3 = saveKoreaTicker(observedAt = Instant.parse("2024-02-01T00:00:00Z"))
        val foreignTicker3 = saveForeignTicker(observedAt = Instant.parse("2024-02-01T00:00:00Z"))
        val fxTicker3 = saveFxTicker(observedAt = Instant.parse("2024-02-01T00:00:00Z"))
        premiumRepository.save(Premium.create(koreaTicker3, foreignTicker3, fxTicker3))

        // when & then
        mockMvc.get("/api/v1/premiums/history/BTC") {
            param("from", "2024-01-01T00:00:00Z")
            param("to", "2024-01-31T23:59:59Z")
        }.andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(2) }
            // observedAt 오름차순 정렬 확인 (2024-01-01 < 2024-01-02)
            jsonPath("$[0].observedAt") { value(org.hamcrest.Matchers.containsString("2024-01-01")) }
            jsonPath("$[1].observedAt") { value(org.hamcrest.Matchers.containsString("2024-01-02")) }
        }
    }

    @Test
    fun `기간 내 데이터 없으면 빈 배열 반환`() {
        // given: 2024-01-01 데이터만 존재
        saveAllTickersAndPremium(observedAt = Instant.parse("2024-01-01T00:00:00Z"))

        // when & then: 2024-03월 기간으로 조회
        mockMvc.get("/api/v1/premiums/history/BTC") {
            param("from", "2024-03-01T00:00:00Z")
            param("to", "2024-03-31T23:59:59Z")
        }.andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(0) }
        }
    }
}
