package io.premiumspread.application.job.premium

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.premiumspread.application.common.JobExecutor
import io.premiumspread.application.common.JobResult
import io.premiumspread.domain.batch.BatchMarket
import io.premiumspread.domain.batch.BatchMarketProvider
import io.premiumspread.domain.exchangerate.ExchangeRateSnapshot
import io.premiumspread.domain.market.FxRateReadPort
import io.premiumspread.domain.market.MarketPair
import io.premiumspread.domain.market.TickerReadPort
import io.premiumspread.domain.premium.PremiumRealtimeWritePort
import io.premiumspread.domain.premium.PremiumSnapshot
import io.premiumspread.domain.premium.PremiumThresholdEvaluator
import io.premiumspread.domain.ticker.Exchange
import io.premiumspread.domain.ticker.Currency
import io.premiumspread.domain.ticker.Quote
import io.premiumspread.domain.ticker.Symbol
import io.premiumspread.domain.ticker.TickerSnapshot
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

class PremiumRealtimeJobTest {
    private val tickerReader = mockk<TickerReadPort>()
    private val fxReader = mockk<FxRateReadPort>()
    private val writer = mockk<PremiumRealtimeWritePort>(relaxed = true)
    private val evaluator = mockk<PremiumThresholdEvaluator>(relaxed = true)
    private val marketProvider = mockk<BatchMarketProvider>()
    private val executor = mockk<JobExecutor>()
    private lateinit var job: PremiumRealtimeJob
    private val now = Instant.parse("2026-07-14T00:00:00Z")

    @BeforeEach
    fun setUp() {
        every { executor.execute(any(), any()) } answers { secondArg<() -> JobResult>().invoke() }
        every { marketProvider.defaultMarket() } returns market()
        job = PremiumRealtimeJob(tickerReader, fxReader, writer, evaluator, marketProvider, executor)
    }

    @Test
    fun `필수 market snapshot이 없으면 skip한다`() {
        every { tickerReader.findLatest(Exchange.BITHUMB, any()) } returns null
        every { tickerReader.findLatest(Exchange.BINANCE, any()) } returns ticker(Exchange.BINANCE, "89277")
        every { fxReader.findLatest(any(), any()) } returns fx()

        val result = job.run()

        assertThat((result as JobResult.Skipped).reason).isEqualTo("missing_data")
        verify(exactly = 0) { writer.saveCurrent(any()) }
    }

    @Test
    fun `가격이 0 이하면 skip한다`() {
        every { tickerReader.findLatest(Exchange.BITHUMB, any()) } returns ticker(Exchange.BITHUMB, "0")
        every { tickerReader.findLatest(Exchange.BINANCE, any()) } returns ticker(Exchange.BINANCE, "89277")
        every { fxReader.findLatest(any(), any()) } returns fx()

        assertThat((job.run() as JobResult.Skipped).reason).isEqualTo("invalid_price")
    }

    @Test
    fun `계산 결과를 current seconds history에 쓰고 evaluator에 전달한다`() {
        every { tickerReader.findLatest(Exchange.BITHUMB, any()) } returns ticker(Exchange.BITHUMB, "129555000")
        every { tickerReader.findLatest(Exchange.BINANCE, any()) } returns ticker(Exchange.BINANCE, "89277")
        every { fxReader.findLatest(any(), any()) } returns fx()
        val captured = slot<PremiumSnapshot>()

        assertThat(job.run()).isEqualTo(JobResult.Success)

        verify { writer.saveCurrent(capture(captured)) }
        verify { writer.saveSecond(captured.captured) }
        verify { writer.saveHistory(captured.captured) }
        verify { evaluator.evaluate(captured.captured) }
        assertThat(captured.captured.pair.koreaExchange).isEqualTo(Exchange.BITHUMB)
        assertThat(captured.captured.pair.foreignExchange).isEqualTo(Exchange.BINANCE)
    }

    @Test
    fun `history 저장 실패는 current 계산을 실패시키지 않는다`() {
        every { tickerReader.findLatest(Exchange.BITHUMB, any()) } returns ticker(Exchange.BITHUMB, "129555000")
        every { tickerReader.findLatest(Exchange.BINANCE, any()) } returns ticker(Exchange.BINANCE, "89277")
        every { fxReader.findLatest(any(), any()) } returns fx()
        every { writer.saveHistory(any()) } throws IllegalStateException("history")

        assertThat(job.run()).isEqualTo(JobResult.Success)
        verify { evaluator.evaluate(any()) }
    }

    private fun ticker(exchange: Exchange, price: String) = TickerSnapshot(
        exchange = exchange.name,
        symbol = "BTC",
        currency = if (exchange == Exchange.BITHUMB) "KRW" else "USD",
        price = BigDecimal(price),
        volume = null,
        observedAt = now,
    )

    private fun fx() = ExchangeRateSnapshot("USD", "KRW", BigDecimal("1432.6"), now)

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
