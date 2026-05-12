package io.premiumspread.application.job.ticker

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import io.premiumspread.application.common.JobResult
import io.premiumspread.cache.TickerCacheService
import io.premiumspread.client.TickerData
import io.premiumspread.client.binance.BinanceClient
import io.premiumspread.client.bithumb.BithumbClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

class TickerIngestionJobModeTest {

    private val bithumbClient = mockk<BithumbClient>()
    private val binanceClient = mockk<BinanceClient>()
    private val cache = mockk<TickerCacheService>(relaxed = true)

    @Test
    fun `두 거래소 모두 rest면 둘 다 fetch한다`() {
        coEvery { bithumbClient.getBtcTicker() } returns ticker("BITHUMB")
        coEvery { binanceClient.getBtcFuturesTicker() } returns ticker("BINANCE")
        val job = TickerIngestionJob(bithumbClient, binanceClient, cache, binanceMode = "rest", bithumbMode = "rest")

        val result = job.run()

        assertThat(result).isEqualTo(JobResult.Success)
        coVerify(exactly = 1) { bithumbClient.getBtcTicker() }
        coVerify(exactly = 1) { binanceClient.getBtcFuturesTicker() }
        verify(exactly = 2) { cache.save(any()) }
    }

    @Test
    fun `binance만 websocket이면 binance REST는 호출되지 않는다`() {
        coEvery { bithumbClient.getBtcTicker() } returns ticker("BITHUMB")
        val job = TickerIngestionJob(bithumbClient, binanceClient, cache, binanceMode = "websocket", bithumbMode = "rest")

        val result = job.run()

        assertThat(result).isEqualTo(JobResult.Success)
        coVerify(exactly = 0) { binanceClient.getBtcFuturesTicker() }
        coVerify(exactly = 1) { bithumbClient.getBtcTicker() }
    }

    @Test
    fun `bithumb만 websocket이면 bithumb REST는 호출되지 않는다`() {
        coEvery { binanceClient.getBtcFuturesTicker() } returns ticker("BINANCE")
        val job = TickerIngestionJob(bithumbClient, binanceClient, cache, binanceMode = "rest", bithumbMode = "websocket")

        val result = job.run()

        assertThat(result).isEqualTo(JobResult.Success)
        coVerify(exactly = 0) { bithumbClient.getBtcTicker() }
        coVerify(exactly = 1) { binanceClient.getBtcFuturesTicker() }
    }

    @Test
    fun `둘 다 websocket이면 아무 REST도 호출하지 않고 Success를 반환한다`() {
        val job = TickerIngestionJob(bithumbClient, binanceClient, cache, binanceMode = "websocket", bithumbMode = "websocket")

        val result = job.run()

        assertThat(result).isEqualTo(JobResult.Success)
        coVerify(exactly = 0) { bithumbClient.getBtcTicker() }
        coVerify(exactly = 0) { binanceClient.getBtcFuturesTicker() }
        verify(exactly = 0) { cache.save(any()) }
    }

    private fun ticker(exchange: String) = TickerData(
        exchange = exchange,
        symbol = "BTC",
        currency = if (exchange == "BITHUMB") "KRW" else "USDT",
        price = BigDecimal("100"),
        volume = null,
        timestamp = Instant.now(),
    )
}
