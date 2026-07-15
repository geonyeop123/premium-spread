package io.premiumspread.application.job.ticker

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.premiumspread.application.common.JobExecutor
import io.premiumspread.application.common.JobResult
import io.premiumspread.domain.batch.BatchMarket
import io.premiumspread.domain.batch.BatchMarketProvider
import io.premiumspread.domain.market.LatestMarketTickReadPort
import io.premiumspread.domain.market.MarketPair
import io.premiumspread.domain.market.MarketTick
import io.premiumspread.domain.market.TickerTimeSeriesWritePort
import io.premiumspread.domain.market.TickerFlushObserver
import io.premiumspread.domain.ticker.Currency
import io.premiumspread.domain.ticker.Exchange
import io.premiumspread.domain.ticker.Quote
import io.premiumspread.domain.ticker.Symbol
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class TickerFlushJobsTest {
    private val reader = mockk<LatestMarketTickReadPort>()
    private val writer = mockk<TickerTimeSeriesWritePort>(relaxed = true)
    private val observer = mockk<TickerFlushObserver>(relaxed = true)
    private val executor = mockk<JobExecutor>()
    private val marketProvider = mockk<BatchMarketProvider>()
    private val now = Instant.parse("2026-07-14T00:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val market = market()

    @BeforeEach
    fun setUp() {
        every { executor.execute(any(), any()) } answers { secondArg<() -> JobResult>().invoke() }
        every { marketProvider.defaultMarket() } returns market
    }

    @Test
    fun `fresh latest tick은 seconds writer에 전달한다`() {
        val tick = MarketTick(Exchange.BINANCE, market.foreignQuote, BigDecimal("10"), now.minusSeconds(1))
        every { reader.findLatest(Exchange.BINANCE, market.foreignQuote) } returns tick

        val result = BinanceTickerFlushJob(
            marketProvider,
            reader,
            writer,
            executor,
            clock,
            observer = observer,
        ).run()

        assertThat(result).isEqualTo(JobResult.Success)
        verify { writer.saveSecond(tick, now) }
        verify { observer.succeeded(Exchange.BINANCE) }
    }

    @Test
    fun `stale tick은 저장하지 않는다`() {
        every { reader.findLatest(Exchange.BITHUMB, market.koreaQuote) } returns
            MarketTick(Exchange.BITHUMB, market.koreaQuote, BigDecimal("10"), now.minusSeconds(11))

        val result = BithumbTickerFlushJob(
            marketProvider,
            reader,
            writer,
            executor,
            clock,
            observer = observer,
        ).run()

        assertThat((result as JobResult.Skipped).reason).isEqualTo("stale_data")
        verify(exactly = 0) { writer.saveSecond(any(), any()) }
        verify { observer.stale(Exchange.BITHUMB) }
    }

    @Test
    fun `flush 실패는 observer에 원인을 전달한다`() {
        val tick = MarketTick(Exchange.BINANCE, market.foreignQuote, BigDecimal("10"), now.minusSeconds(1))
        val failure = IllegalStateException("redis unavailable")
        every { reader.findLatest(Exchange.BINANCE, market.foreignQuote) } returns tick
        every { writer.saveSecond(tick, now) } throws failure

        val result = BinanceTickerFlushJob(
            marketProvider,
            reader,
            writer,
            executor,
            clock,
            observer = observer,
        ).run()

        assertThat((result as JobResult.Failure).exception).isSameAs(failure)
        verify { observer.failed(Exchange.BINANCE, failure) }
    }

    private fun market(): BatchMarket {
        val symbol = Symbol("BTC")
        return BatchMarket(
            MarketPair.default(symbol),
            Quote.coin(symbol, Currency.KRW),
            Quote.coin(symbol, Currency.USD),
            Currency.USD,
            Currency.KRW,
        )
    }
}
