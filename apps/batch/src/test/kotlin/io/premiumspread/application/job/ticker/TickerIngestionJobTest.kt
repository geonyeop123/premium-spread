package io.premiumspread.application.job.ticker

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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
        // 기본 동작은 양쪽 모두 rest mode
        job = TickerIngestionJob(
            bithumbClient = bithumbClient,
            binanceClient = binanceClient,
            tickerCacheService = tickerCacheService,
            binanceMode = "rest",
            bithumbMode = "rest",
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
            verify { tickerCacheService.save(bithumb) }
            verify { tickerCacheService.save(binance) }
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

        @Test
        fun `캐시 저장 예외 시 Failure를 반환한다`() {
            // given
            coEvery { bithumbClient.getBtcTicker() } returns bithumbTicker()
            coEvery { binanceClient.getBtcFuturesTicker() } returns binanceTicker()
            every { tickerCacheService.save(any()) } throws RuntimeException("redis error")

            // when
            val result = job.run()

            // then
            assertThat(result).isInstanceOf(JobResult.Failure::class.java)
            assertThat((result as JobResult.Failure).exception.message).isEqualTo("redis error")
        }

        @Test
        fun `binance 실패가 bithumb await보다 늦게 발생해도 bithumb cache write는 일어나지 않는다 (atomic await)`() {
            // given — binance가 throw하지만 bithumb은 성공
            coEvery { bithumbClient.getBtcTicker() } returns bithumbTicker()
            coEvery { binanceClient.getBtcFuturesTicker() } throws RuntimeException("binance failed")

            // when
            val result = job.run()

            // then — Failure 반환 + 두 await 모두 끝난 뒤 write를 시도하므로 어떤 save/saveToSeconds도 호출 안 됨
            assertThat(result).isInstanceOf(JobResult.Failure::class.java)
            verify(exactly = 0) { tickerCacheService.save(any()) }
            verify(exactly = 0) { tickerCacheService.saveToSeconds(any()) }
        }
    }
}
