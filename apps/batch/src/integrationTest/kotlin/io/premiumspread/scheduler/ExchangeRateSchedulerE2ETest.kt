package io.premiumspread.interfaces.scheduling

import com.fasterxml.jackson.databind.ObjectMapper
import io.premiumspread.application.job.fx.FxIngestionJob
import io.premiumspread.infrastructure.batch.exchange.ExchangeRateResponse
import io.premiumspread.support.BatchIntegrationTestBase
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal

@DisplayName("ExchangeRateScheduler E2E")
class ExchangeRateSchedulerE2ETest : BatchIntegrationTestBase() {

    @Autowired
    lateinit var exchangeRateJob: FxIngestionJob

    @Autowired
    lateinit var exchangeRateMockServer: MockWebServer

    private val objectMapper = ObjectMapper().apply { findAndRegisterModules() }

    private fun enqueueSuccessResponse(rate: BigDecimal = BigDecimal("1432.60")) {
        val response = ExchangeRateResponse(
            result = "success",
            baseCode = "USD",
            targetCode = "KRW",
            conversionRate = rate,
            timeLastUpdateUnix = 1706486401L,
        )
        exchangeRateMockServer.enqueue(
            MockResponse()
                .setBody(objectMapper.writeValueAsString(response))
                .addHeader("Content-Type", "application/json"),
        )
    }

    @Nested
    @DisplayName("fetchExchangeRate")
    inner class FetchExchangeRate {

        @Test
        fun `실행 후 환율 캐시가 저장된다`() {
            // given
            enqueueSuccessResponse()

            // when
            exchangeRateJob.run()

            // then
            val hash = redisTemplate.opsForHash<String, String>().entries("fx:usd:krw")
            assertThat(hash).isNotEmpty
            assertThat(hash["base"]).isEqualTo("USD")
            assertThat(hash["quote"]).isEqualTo("KRW")
            assertThat(BigDecimal(hash["rate"])).isEqualByComparingTo(BigDecimal("1432.60"))
            assertThat(hash["timestamp"]).isNotNull()
        }

        @Test
        fun `실행 후 환율 DB 레코드가 저장된다`() {
            // given
            enqueueSuccessResponse()

            // when
            exchangeRateJob.run()

            // then
            val results = jdbcTemplate.queryForList(
                "SELECT * FROM exchange_rate WHERE base_currency = 'USD' AND quote_currency = 'KRW'",
            )
            assertThat(results).hasSize(1)
            assertThat(results[0]["rate"] as BigDecimal).isEqualByComparingTo(BigDecimal("1432.60"))
            assertThat(results[0]["observed_at"]).isNotNull()
        }

        @Test
        fun `API 최종 실패 시 Redis 캐시와 DB 레코드가 저장되지 않는다`() {
            // given - 3회 모두 서버 오류
            repeat(3) { exchangeRateMockServer.enqueue(MockResponse().setResponseCode(500)) }

            // when
            exchangeRateJob.run()

            // then - 캐시 없음
            assertThat(redisTemplate.opsForHash<String, String>().entries("fx:usd:krw")).isEmpty()

            // then - DB 레코드 없음
            val results = jdbcTemplate.queryForList(
                "SELECT * FROM exchange_rate WHERE base_currency = 'USD' AND quote_currency = 'KRW'",
            )
            assertThat(results).isEmpty()
        }
    }

    @Nested
    @DisplayName("fetchExchangeRateOnStartup")
    inner class FetchExchangeRateOnStartup {

        @Test
        fun `실행 시 fetchExchangeRate와 동일하게 캐시와 DB가 저장된다`() {
            // given
            enqueueSuccessResponse()

            // when
            exchangeRateJob.run()

            // then - 캐시 검증
            val hash = redisTemplate.opsForHash<String, String>().entries("fx:usd:krw")
            assertThat(hash).isNotEmpty
            assertThat(BigDecimal(hash["rate"])).isEqualByComparingTo(BigDecimal("1432.60"))

            // then - DB 검증
            val results = jdbcTemplate.queryForList(
                "SELECT * FROM exchange_rate WHERE base_currency = 'USD' AND quote_currency = 'KRW'",
            )
            assertThat(results).hasSize(1)
        }
    }
}
