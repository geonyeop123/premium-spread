package io.premiumspread.interfaces.scheduling

import io.premiumspread.application.job.fx.FxIngestionJob
import io.premiumspread.support.BatchIntegrationTestBase
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Instant
import java.util.concurrent.TimeUnit

@DisplayName("ExchangeRateScheduler E2E")
class ExchangeRateSchedulerE2ETest : BatchIntegrationTestBase() {

    @Autowired
    lateinit var exchangeRateJob: FxIngestionJob

    @Autowired
    lateinit var exchangeRateMockServer: MockWebServer

    private fun enqueueSuccessResponse() {
        exchangeRateMockServer.enqueue(
            MockResponse()
                .setBody("""{"symbol":"USD/KRW","rate":1234.5678,"timestamp":1767225600}""")
                .addHeader("Content-Type", "application/json"),
        )
    }

    // 공유 mock server 에 이전 테스트의 미소비 요청이 남아 있을 수 있어 enqueue 전에 비운다.
    private fun drainRecordedRequests() {
        while (exchangeRateMockServer.takeRequest(0, TimeUnit.MILLISECONDS) != null) {
            // drain
        }
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
            assertThat(BigDecimal(hash["rate"])).isEqualByComparingTo(BigDecimal("1234.5678"))
            assertThat(hash["timestamp"]).isEqualTo("1767225600000")

            val ttlSeconds = redisTemplate.getExpire("fx:usd:krw", TimeUnit.SECONDS)
            assertThat(ttlSeconds).isGreaterThan(2100L).isLessThanOrEqualTo(2400L)
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
            assertThat(results[0]["rate"] as BigDecimal).isEqualByComparingTo(BigDecimal("1234.5678"))
            // 쓰기(Timestamp.from)와 읽기(Timestamp)가 같은 JVM, 같은 드라이버의 JDBC Timestamp 왕복이라 대칭이다.
            val observedAt = jdbcTemplate.queryForObject(
                "SELECT observed_at FROM exchange_rate WHERE base_currency = 'USD' AND quote_currency = 'KRW'",
                Timestamp::class.java,
            )
            assertThat(observedAt!!.toInstant()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"))
        }

        @Test
        fun `요청은 Authorization 헤더에 apikey 를 싣는다`() {
            // given
            drainRecordedRequests()
            enqueueSuccessResponse()

            // when
            exchangeRateJob.run()

            // then
            val request = exchangeRateMockServer.takeRequest(1, TimeUnit.SECONDS)
            assertThat(request).isNotNull
            assertThat(request!!.getHeader("Authorization")).isEqualTo("apikey test-dummy-key")
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
            assertThat(BigDecimal(hash["rate"])).isEqualByComparingTo(BigDecimal("1234.5678"))

            // then - DB 검증
            val results = jdbcTemplate.queryForList(
                "SELECT * FROM exchange_rate WHERE base_currency = 'USD' AND quote_currency = 'KRW'",
            )
            assertThat(results).hasSize(1)
        }
    }
}
