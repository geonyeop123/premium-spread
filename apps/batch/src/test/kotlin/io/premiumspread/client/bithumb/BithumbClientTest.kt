package io.premiumspread.client.bithumb

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

class BithumbClientTest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var bithumbClient: BithumbClient
    private lateinit var meterRegistry: SimpleMeterRegistry
    private val objectMapper = ObjectMapper()

    @BeforeEach
    fun setUp() {
        mockWebServer = MockWebServer()
        mockWebServer.start()

        meterRegistry = SimpleMeterRegistry()
        val webClient = WebClient.builder()
            .baseUrl(mockWebServer.url("/").toString())
            .build()

        bithumbClient = BithumbClient(webClient, meterRegistry)
    }

    @AfterEach
    fun tearDown() {
        mockWebServer.shutdown()
    }

    @Nested
    @DisplayName("getBtcTicker")
    inner class GetBtcTicker {

        @Test
        fun `should return ticker data on success`() = runBlocking {
            // given
            val response = BithumbTickerResponse(
                status = "0000",
                data = BithumbTickerData(
                    openingPrice = "128000000",
                    closingPrice = "129555000",
                    minPrice = "127500000",
                    maxPrice = "130000000",
                    unitsTraded24H = "1234.5678",
                    date = "1706500000000",
                ),
            )
            mockWebServer.enqueue(
                MockResponse()
                    .setBody(objectMapper.writeValueAsString(response))
                    .addHeader("Content-Type", "application/json"),
            )

            // when
            val ticker = bithumbClient.getBtcTicker()

            // then
            assertThat(ticker.exchange).isEqualTo("BITHUMB")
            assertThat(ticker.symbol).isEqualTo("BTC")
            assertThat(ticker.currency).isEqualTo("KRW")
            assertThat(ticker.price).isEqualByComparingTo(BigDecimal("129555000"))
            assertThat(ticker.volume).isEqualByComparingTo(BigDecimal("1234.5678"))
        }

        @Test
        fun `should throw exception on API error status`() {
            // given
            val response = BithumbTickerResponse(
                status = "5000",
                message = "Bad Request",
                data = null,
            )
            mockWebServer.enqueue(
                MockResponse()
                    .setBody(objectMapper.writeValueAsString(response))
                    .addHeader("Content-Type", "application/json"),
            )

            // when & then
            assertThatThrownBy { runBlocking { bithumbClient.getBtcTicker() } }
                .isInstanceOf(BithumbApiException::class.java)
                .hasMessageContaining("status=5000")
        }

        @Test
        fun `should retry on failure and succeed`() = runBlocking {
            // given
            mockWebServer.enqueue(MockResponse().setResponseCode(500))
            mockWebServer.enqueue(
                MockResponse()
                    .setBody(
                        objectMapper.writeValueAsString(
                            BithumbTickerResponse(
                                status = "0000",
                                data = BithumbTickerData(
                                    openingPrice = "128000000",
                                    closingPrice = "129555000",
                                    minPrice = "127500000",
                                    maxPrice = "130000000",
                                ),
                            ),
                        ),
                    )
                    .addHeader("Content-Type", "application/json"),
            )

            // when
            val ticker = bithumbClient.getBtcTicker()

            // then
            assertThat(ticker.price).isEqualByComparingTo(BigDecimal("129555000"))
            assertThat(mockWebServer.requestCount).isEqualTo(2)
        }

        @Test
        fun `should record metrics on success`() = runBlocking {
            // given
            mockWebServer.enqueue(
                MockResponse()
                    .setBody(
                        objectMapper.writeValueAsString(
                            BithumbTickerResponse(
                                status = "0000",
                                data = BithumbTickerData(
                                    openingPrice = "128000000",
                                    closingPrice = "129555000",
                                    minPrice = "127500000",
                                    maxPrice = "130000000",
                                ),
                            ),
                        ),
                    )
                    .addHeader("Content-Type", "application/json"),
            )

            // when
            bithumbClient.getBtcTicker()

            // then
            val successCounter = meterRegistry.find("ticker.fetch.success")
                .tag("exchange", "BITHUMB")
                .counter()
            assertThat(successCounter?.count()).isEqualTo(1.0)

            val latencyTimer = meterRegistry.find("ticker.fetch.latency")
                .tag("exchange", "BITHUMB")
                .timer()
            assertThat(latencyTimer?.count()).isEqualTo(1)
        }
    }
}
