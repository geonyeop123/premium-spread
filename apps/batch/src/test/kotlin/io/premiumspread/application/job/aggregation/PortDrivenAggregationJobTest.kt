package io.premiumspread.application.job.aggregation

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.premiumspread.application.common.JobExecutor
import io.premiumspread.application.common.JobResult
import io.premiumspread.config.AggregationProperties
import io.premiumspread.domain.aggregation.AggregationUnit
import io.premiumspread.domain.aggregation.PremiumAggregatePort
import io.premiumspread.domain.aggregation.TickerAggregatePort
import io.premiumspread.domain.aggregation.TickerAggregationSnapshot
import io.premiumspread.domain.batch.BatchMarket
import io.premiumspread.domain.batch.BatchMarketProvider
import io.premiumspread.domain.market.MarketPair
import io.premiumspread.domain.premium.PremiumAggregationSnapshot
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

class PortDrivenAggregationJobTest {
    private val premiumPort = mockk<PremiumAggregatePort>(relaxed = true)
    private val tickerPort = mockk<TickerAggregatePort>(relaxed = true)
    private val marketProvider = mockk<BatchMarketProvider>()
    private val executor = mockk<JobExecutor>()
    private val clock = Clock.fixed(Instant.parse("2026-07-14T12:34:56Z"), ZoneOffset.UTC)
    private val windowPolicy = AggregationWindowPolicy(AggregationProperties())
    private val market = market()

    @BeforeEach
    fun setUp() {
        every { executor.execute(any(), any()) } answers { secondArg<() -> JobResult>().invoke() }
        every { marketProvider.defaultMarket() } returns market
    }

    @Test
    fun `premium minute job은 raw aggregate를 읽고 같은 window에 저장한다`() {
        val snapshot = premiumSnapshot()
        every { premiumPort.aggregate(market.pair, null, any()) } returns snapshot
        val job = PremiumAggregationJob(premiumPort, marketProvider, executor, clock, windowPolicy)

        assertThat(job.aggregateMinute()).isEqualTo(JobResult.Success)
        verify { premiumPort.save(AggregationUnit.MINUTE, any(), snapshot) }
    }

    @Test
    fun `ticker hour job은 configured 양 거래소를 minute source에서 집계한다`() {
        every { tickerPort.aggregate(any(), any(), AggregationUnit.MINUTE, any()) } answers {
            tickerSnapshot(firstArg(), secondArg())
        }
        val job = TickerAggregationJob(tickerPort, marketProvider, executor, clock, windowPolicy)

        assertThat(job.aggregateHour()).isEqualTo(JobResult.Success)
        verify(exactly = 1) { tickerPort.aggregate(Exchange.BITHUMB, market.koreaQuote, AggregationUnit.MINUTE, any()) }
        verify(exactly = 1) { tickerPort.aggregate(Exchange.BINANCE, market.foreignQuote, AggregationUnit.MINUTE, any()) }
        verify(exactly = 2) { tickerPort.save(AggregationUnit.HOUR, any(), any()) }
    }

    private fun premiumSnapshot() = PremiumAggregationSnapshot(
        pair = market.pair,
        high = BigDecimal("2"),
        low = BigDecimal("1"),
        open = BigDecimal("1"),
        close = BigDecimal("2"),
        avg = BigDecimal("1.5"),
        count = 2,
        observedAt = clock.instant(),
    )

    private fun tickerSnapshot(exchange: Exchange, quote: Quote) = TickerAggregationSnapshot(
        exchange,
        quote,
        BigDecimal("2"),
        BigDecimal("1"),
        BigDecimal("1"),
        BigDecimal("2"),
        BigDecimal("1.5"),
        2,
        clock.instant(),
    )

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
