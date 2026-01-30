package io.premiumspread.client.binance

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

class BinanceClientTest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var binanceClient: BinanceClient
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

        binanceClient = BinanceClient(webClient, meterRegistry)
    }

    @AfterEach
    fun tearDown() {
        mockWebServer.shutdown()
    }

    @Nested
    @DisplayName("getBtcFuturesTicker")
    inner class GetBtcFuturesTicker {

        @Test
        fun `should return ticker data on success`() = runBlocking {
            // given
            val response = BinancePriceResponse(
                symbol = "BTCUSDT",
                price = "89277.10",
                time = 1706500000000L,
            )
            mockWebServer.enqueue(
                MockResponse()
                    .setBody(objectMapper.writeValueAsString(response))
                    .addHeader("Content-Type", "application/json"),
            )

            // when
            val ticker = binanceClient.getBtcFuturesTicker()

            // then
            assertThat(ticker.exchange).isEqualTo("BINANCE")
            assertThat(ticker.symbol).isEqualTo("BTC")
            assertThat(ticker.currency).isEqualTo("USDT")
            assertThat(ticker.price).isEqualByComparingTo(BigDecimal("89277.10"))
        }

        @Test
        fun `should throw exception on invalid price`() {
            // given
            val response = BinancePriceResponse(
                symbol = "BTCUSDT",
                price = "invalid",
                time = 1706500000000L,
            )
            mockWebServer.enqueue(
                MockResponse()
                    .setBody(objectMapper.writeValueAsString(response))
                    .addHeader("Content-Type", "application/json"),
            )

            // when & then
            assertThatThrownBy { runBlocking { binanceClient.getBtcFuturesTicker() } }
                .isInstanceOf(BinanceApiException::class.java)
                .hasMessageContaining("Invalid price")
        }

        @Test
        fun `should retry on failure and succeed`() = runBlocking {
            // given
            mockWebServer.enqueue(MockResponse().setResponseCode(500))
            mockWebServer.enqueue(
                MockResponse()
                    .setBody(
                        objectMapper.writeValueAsString(
                            BinancePriceResponse(
                                symbol = "BTCUSDT",
                                price = "89277.10",
                            ),
                        ),
                    )
                    .addHeader("Content-Type", "application/json"),
            )

            // when
            val ticker = binanceClient.getBtcFuturesTicker()

            // then
            assertThat(ticker.price).isEqualByComparingTo(BigDecimal("89277.10"))
            assertThat(mockWebServer.requestCount).isEqualTo(2)
        }

        @Test
        fun `should record metrics on success`() = runBlocking {
            // given
            mockWebServer.enqueue(
                MockResponse()
                    .setBody(
                        objectMapper.writeValueAsString(
                            BinancePriceResponse(
                                symbol = "BTCUSDT",
                                price = "89277.10",
                            ),
                        ),
                    )
                    .addHeader("Content-Type", "application/json"),
            )

            // when
            binanceClient.getBtcFuturesTicker()

            // then
            val successCounter = meterRegistry.find("ticker.fetch.success")
                .tag("exchange", "BINANCE")
                .counter()
            assertThat(successCounter?.count()).isEqualTo(1.0)
        }
    }

    @Nested
    @DisplayName("getFuturesTickerWithVolume")
    inner class GetFuturesTickerWithVolume {

        @Test
        fun `should return ticker data with volume`() = runBlocking {
            // given
            val response = Binance24hrTickerResponse(
                symbol = "BTCUSDT",
                lastPrice = "89277.10",
                volume = "123456.789",
                closeTime = 1706500000000L,
            )
            mockWebServer.enqueue(
                MockResponse()
                    .setBody(objectMapper.writeValueAsString(response))
                    .addHeader("Content-Type", "application/json"),
            )

            // when
            val ticker = binanceClient.getFuturesTickerWithVolume("BTCUSDT")

            // then
            assertThat(ticker.exchange).isEqualTo("BINANCE")
            assertThat(ticker.symbol).isEqualTo("BTC")
            assertThat(ticker.price).isEqualByComparingTo(BigDecimal("89277.10"))
            assertThat(ticker.volume).isEqualByComparingTo(BigDecimal("123456.789"))
        }
    }
}
