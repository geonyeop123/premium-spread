package io.premiumspread.scheduler

import com.fasterxml.jackson.databind.ObjectMapper
import io.premiumspread.client.binance.BinancePriceResponse
import io.premiumspread.client.bithumb.BithumbTickerData
import io.premiumspread.client.bithumb.BithumbTickerResponse
import io.premiumspread.support.BatchIntegrationTestBase
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.time.Instant

@DisplayName("TickerScheduler E2E")
class TickerSchedulerE2ETest : BatchIntegrationTestBase() {

    @Autowired
    lateinit var tickerScheduler: TickerScheduler

    @Autowired
    lateinit var bithumbMockServer: MockWebServer

    @Autowired
    lateinit var binanceMockServer: MockWebServer

    private val objectMapper = ObjectMapper().apply { findAndRegisterModules() }

    private val nowMillis get() = Instant.now().toEpochMilli()

    private fun enqueueBithumbResponse(price: String = "129555000", volume: String = "2345.6789") {
        val response = BithumbTickerResponse(
            status = "0000",
            data = BithumbTickerData(
                openingPrice = "129000000",
                closingPrice = price,
                minPrice = "128000000",
                maxPrice = "130000000",
                unitsTraded24H = volume,
                date = nowMillis.toString(),
            ),
        )
        bithumbMockServer.enqueue(
            MockResponse()
                .setBody(objectMapper.writeValueAsString(response))
                .addHeader("Content-Type", "application/json"),
        )
    }

    private fun enqueueBinanceResponse(price: String = "89277.10") {
        val response = BinancePriceResponse(
            symbol = "BTCUSDT",
            price = price,
            time = nowMillis,
        )
        binanceMockServer.enqueue(
            MockResponse()
                .setBody(objectMapper.writeValueAsString(response))
                .addHeader("Content-Type", "application/json"),
        )
    }

    @Nested
    @DisplayName("fetchTickers")
    inner class FetchTickers {

        @Test
        fun `실행 후 빗썸과 바이낸스 티커 캐시가 저장된다`() {
            // given
            enqueueBithumbResponse()
            enqueueBinanceResponse()

            // when
            tickerScheduler.fetchTickers()

            // then - 빗썸 Hash 검증
            val bithumbHash = redisTemplate.opsForHash<String, String>().entries("ticker:bithumb:btc")
            assertThat(bithumbHash).isNotEmpty
            assertThat(bithumbHash["exchange"]).isEqualTo("BITHUMB")
            assertThat(bithumbHash["symbol"]).isEqualTo("BTC")
            assertThat(bithumbHash["currency"]).isEqualTo("KRW")
            assertThat(BigDecimal(bithumbHash["price"])).isEqualByComparingTo(BigDecimal("129555000"))
            assertThat(bithumbHash["volume"]).isEqualTo("2345.6789")

            // then - 바이낸스 Hash 검증
            val binanceHash = redisTemplate.opsForHash<String, String>().entries("ticker:binance:btc")
            assertThat(binanceHash).isNotEmpty
            assertThat(binanceHash["exchange"]).isEqualTo("BINANCE")
            assertThat(binanceHash["symbol"]).isEqualTo("BTC")
            assertThat(binanceHash["currency"]).isEqualTo("USDT")
            assertThat(BigDecimal(binanceHash["price"])).isEqualByComparingTo(BigDecimal("89277.10"))
        }

        @Test
        fun `실행 후 빗썸 초당 ZSet에 데이터가 저장된다`() {
            // given
            enqueueBithumbResponse()
            enqueueBinanceResponse()

            // when
            tickerScheduler.fetchTickers()

            // then
            val zsetSize = redisTemplate.opsForZSet().size("ticker:seconds:bithumb:btc")
            assertThat(zsetSize).isGreaterThanOrEqualTo(1)

            val members = redisTemplate.opsForZSet().rangeWithScores("ticker:seconds:bithumb:btc", 0, -1)
            assertThat(members).isNotEmpty
            val entry = members!!.first()
            assertThat(BigDecimal(entry.value!!)).isEqualByComparingTo(BigDecimal("129555000"))
        }

        @Test
        fun `실행 후 바이낸스 초당 ZSet에 데이터가 저장된다`() {
            // given
            enqueueBithumbResponse()
            enqueueBinanceResponse()

            // when
            tickerScheduler.fetchTickers()

            // then
            val zsetSize = redisTemplate.opsForZSet().size("ticker:seconds:binance:btc")
            assertThat(zsetSize).isGreaterThanOrEqualTo(1)

            val members = redisTemplate.opsForZSet().rangeWithScores("ticker:seconds:binance:btc", 0, -1)
            assertThat(members).isNotEmpty
            val entry = members!!.first()
            assertThat(BigDecimal(entry.value!!)).isEqualByComparingTo(BigDecimal("89277.10"))
        }
    }
}
