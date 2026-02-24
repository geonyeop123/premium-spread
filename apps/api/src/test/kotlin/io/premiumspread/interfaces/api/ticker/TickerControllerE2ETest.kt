package io.premiumspread.interfaces.api.ticker

import com.fasterxml.jackson.databind.ObjectMapper
import io.premiumspread.config.TestConfig
import io.premiumspread.testcontainers.MySqlTestContainersConfig
import io.premiumspread.testcontainers.RedisTestContainersConfig
import io.premiumspread.utils.DatabaseCleanUp
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import java.math.BigDecimal
import java.time.Instant

@Tag("integration")
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(MySqlTestContainersConfig::class, RedisTestContainersConfig::class, TestConfig::class)
class TickerControllerE2ETest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val objectMapper: ObjectMapper,
    private val databaseCleanUp: DatabaseCleanUp,
    private val redisTemplate: StringRedisTemplate,
) {

    @BeforeEach
    fun setUp() {
        databaseCleanUp.truncateAllTables()
        redisTemplate.execute { it.serverCommands().flushAll() }
    }

    @Test
    fun `티커 등록 성공 - DB에 1건 저장되고 201 반환`() {
        // given
        val request = mapOf(
            "exchange" to "BITHUMB",
            "baseCode" to "BTC",
            "quoteCurrency" to "KRW",
            "price" to BigDecimal("129555000"),
            "observedAt" to "2024-01-01T00:00:00Z",
        )

        // when & then
        mockMvc.post("/api/v1/tickers") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(request)
        }.andExpect {
            status { isCreated() }
            jsonPath("$.id") { isNumber() }
            jsonPath("$.exchange") { value("BITHUMB") }
            jsonPath("$.exchangeRegion") { value("KOREA") }
            jsonPath("$.baseCode") { value("BTC") }
            jsonPath("$.quoteCurrency") { value("KRW") }
        }
    }

    @Test
    fun `잘못된 거래소로 등록 시 400 반환`() {
        // given
        val request = mapOf(
            "exchange" to "INVALID_EXCHANGE",
            "baseCode" to "BTC",
            "quoteCurrency" to "KRW",
            "price" to 129555000,
            "observedAt" to "2024-01-01T00:00:00Z",
        )

        // when & then
        mockMvc.post("/api/v1/tickers") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(request)
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_ARGUMENT") }
        }
    }
}
