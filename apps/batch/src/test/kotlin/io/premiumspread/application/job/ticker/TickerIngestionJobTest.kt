package io.premiumspread.application.job.ticker

import io.mockk.*
import io.premiumspread.application.common.JobResult
import io.premiumspread.cache.TickerCacheService
import io.premiumspread.client.TickerData
import io.premiumspread.client.binance.BinanceClient
import io.premiumspread.client.bithumb.BithumbClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

class TickerIngestionJobTest {

    private lateinit var bithumbClient: BithumbClient
    private lateinit var binanceClient: BinanceClient
    private lateinit var tickerCacheService: TickerCacheService
    private lateinit var job: TickerIngestionJob

    @BeforeEach
    fun setUp() {
        bithumbClient = mockk()
        binanceClient = mockk()
        tickerCacheService = mockk(relaxed = true)
        job = TickerIngestionJob(
            bithumbClient = bithumbClient,
            binanceClient = binanceClient,
            tickerCacheService = tickerCacheService,
        )
    }

    private val now = Instant.now()

    private fun bithumbTicker() = TickerData(
        exchange = "bithumb",
        symbol = "btc",
        currency = "KRW",
        price = BigDecimal("129555000"),
        volume = null,
        timestamp = now,
    )

    private fun binanceTicker() = TickerData(
        exchange = "binance",
        symbol = "btc",
        currency = "USDT",
        price = BigDecimal("89277"),
        volume = null,
        timestamp = now,
    )

    @Nested
    @DisplayName("실행")
    inner class Run {

        @Test
        fun `성공 시 양쪽 티커를 조회하고 저장한다`() {
            // given
            val bithumb = bithumbTicker()
            val binance = binanceTicker()
            coEvery { bithumbClient.getBtcTicker() } returns bithumb
            coEvery { binanceClient.getBtcFuturesTicker() } returns binance

            // when
            val result = job.run()

            // then
            assertThat(result).isEqualTo(JobResult.Success)
            verify { tickerCacheService.saveAll(bithumb, binance) }
            verify { tickerCacheService.saveToSeconds(bithumb) }
            verify { tickerCacheService.saveToSeconds(binance) }
        }

        @Test
        fun `빗썸 클라이언트 예외 시 Failure를 반환한다`() {
            // given
            coEvery { bithumbClient.getBtcTicker() } throws RuntimeException("bithumb api error")
            coEvery { binanceClient.getBtcFuturesTicker() } returns binanceTicker()

            // when
            val result = job.run()

            // then
            assertThat(result).isInstanceOf(JobResult.Failure::class.java)
            assertThat((result as JobResult.Failure).exception.message).isEqualTo("bithumb api error")
        }

        @Test
        fun `바이낸스 클라이언트 예외 시 Failure를 반환한다`() {
            // given
            coEvery { bithumbClient.getBtcTicker() } returns bithumbTicker()
            coEvery { binanceClient.getBtcFuturesTicker() } throws RuntimeException("binance api error")

            // when
            val result = job.run()

            // then
            assertThat(result).isInstanceOf(JobResult.Failure::class.java)
            assertThat((result as JobResult.Failure).exception.message).isEqualTo("binance api error")
        }
    }
}
