package io.premiumspread.client.exchangerate

import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.web.reactive.function.client.WebClient
import java.math.BigDecimal

class ExchangeRateClientTest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var exchangeRateClient: ExchangeRateClient
    private lateinit var meterRegistry: SimpleMeterRegistry
    private val objectMapper = ObjectMapper().apply {
        findAndRegisterModules()
    }

    @BeforeEach
    fun setUp() {
        mockWebServer = MockWebServer()
        mockWebServer.start()

        meterRegistry = SimpleMeterRegistry()
        val webClient = WebClient.builder()
            .baseUrl(mockWebServer.url("/").toString())
            .build()

        exchangeRateClient = ExchangeRateClient(webClient, meterRegistry, "test-api-key")
    }

    @AfterEach
    fun tearDown() {
        mockWebServer.shutdown()
    }

    @Nested
    @DisplayName("getUsdKrwRate")
    inner class GetUsdKrwRate {

        @Test
        fun `should return exchange rate on success`() = runBlocking {
            // given
            val response = ExchangeRateResponse(
                result = "success",
                baseCode = "USD",
                targetCode = "KRW",
                conversionRate = BigDecimal("1432.60"),
                timeLastUpdateUnix = 1706486401L,
            )
            mockWebServer.enqueue(
                MockResponse()
                    .setBody(objectMapper.writeValueAsString(response))
                    .addHeader("Content-Type", "application/json"),
            )

            // when
            val fxRate = exchangeRateClient.getUsdKrwRate()

            // then
            assertThat(fxRate.baseCurrency).isEqualTo("USD")
            assertThat(fxRate.quoteCurrency).isEqualTo("KRW")
            assertThat(fxRate.rate).isEqualByComparingTo(BigDecimal("1432.60"))
        }

        @Test
        fun `should throw exception on API error`() {
            // given - using raw JSON to match exact API response format
            val responseJson = """{"result":"error","error-type":"unsupported-code"}"""
            mockWebServer.enqueue(
                MockResponse()
                    .setBody(responseJson)
                    .addHeader("Content-Type", "application/json"),
            )

            // when & then
            assertThatThrownBy { runBlocking { exchangeRateClient.getUsdKrwRate() } }
                .isInstanceOf(ExchangeRateApiException::class.java)
                .hasMessageContaining("unsupported-code")
        }

        @Test
        fun `should throw exception when conversion rate is null`() {
            // given
            val response = ExchangeRateResponse(
                result = "success",
                baseCode = "USD",
                targetCode = "KRW",
                conversionRate = null,
            )
            mockWebServer.enqueue(
                MockResponse()
                    .setBody(objectMapper.writeValueAsString(response))
                    .addHeader("Content-Type", "application/json"),
            )

            // when & then
            assertThatThrownBy { runBlocking { exchangeRateClient.getUsdKrwRate() } }
                .isInstanceOf(ExchangeRateApiException::class.java)
                .hasMessageContaining("No conversion rate")
        }

        @Test
        fun `should retry on failure and succeed`() = runBlocking {
            // given
            mockWebServer.enqueue(MockResponse().setResponseCode(500))
            mockWebServer.enqueue(
                MockResponse()
                    .setBody(
                        objectMapper.writeValueAsString(
                            ExchangeRateResponse(
                                result = "success",
                                conversionRate = BigDecimal("1432.60"),
                            ),
                        ),
                    )
                    .addHeader("Content-Type", "application/json"),
            )

            // when
            val fxRate = exchangeRateClient.getUsdKrwRate()

            // then
            assertThat(fxRate.rate).isEqualByComparingTo(BigDecimal("1432.60"))
            assertThat(mockWebServer.requestCount).isEqualTo(2)
        }

        @Test
        fun `should record metrics on success`() = runBlocking {
            // given
            mockWebServer.enqueue(
                MockResponse()
                    .setBody(
                        objectMapper.writeValueAsString(
                            ExchangeRateResponse(
                                result = "success",
                                conversionRate = BigDecimal("1432.60"),
                            ),
                        ),
                    )
                    .addHeader("Content-Type", "application/json"),
            )

            // when
            exchangeRateClient.getUsdKrwRate()

            // then
            val successCounter = meterRegistry.find("fx.fetch.success")
                .tag("provider", "EXCHANGERATE_API")
                .counter()
            assertThat(successCounter?.count()).isEqualTo(1.0)

            val latencyTimer = meterRegistry.find("fx.fetch.latency")
                .tag("provider", "EXCHANGERATE_API")
                .timer()
            assertThat(latencyTimer?.count()).isEqualTo(1)
        }
    }

    @Nested
    @DisplayName("when API key is missing")
    inner class ApiKeyMissing {

        @Test
        fun `should throw exception when API key is blank`() {
            // given
            val clientWithoutKey = ExchangeRateClient(
                WebClient.builder().baseUrl(mockWebServer.url("/").toString()).build(),
                meterRegistry,
                "",
            )

            // when & then
            assertThatThrownBy { runBlocking { clientWithoutKey.getUsdKrwRate() } }
                .isInstanceOf(ExchangeRateApiException::class.java)
                .hasMessageContaining("API key is required")
        }
    }
}
