package io.premiumspread.application.job.fx

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import io.premiumspread.application.common.JobExecutor
import io.premiumspread.application.common.JobResult
import io.premiumspread.domain.batch.BatchMarket
import io.premiumspread.domain.batch.BatchMarketProvider
import io.premiumspread.domain.exchangerate.ExchangeRateSnapshot
import io.premiumspread.domain.market.ExchangeRateProvider
import io.premiumspread.domain.market.FxRateCacheWritePort
import io.premiumspread.domain.market.FxRateWritePort
import io.premiumspread.domain.market.MarketPair
import io.premiumspread.domain.ticker.Currency
import io.premiumspread.domain.ticker.Quote
import io.premiumspread.domain.ticker.Symbol
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

class FxIngestionJobTest {
    private val provider = mockk<ExchangeRateProvider>()
    private val writer = mockk<FxRateWritePort>(relaxed = true)
    private val cacheWriter = mockk<FxRateCacheWritePort>(relaxed = true)
    private val marketProvider = mockk<BatchMarketProvider>()
    private val executor = mockk<JobExecutor>()
    private lateinit var job: FxIngestionJob
    private val snapshot = ExchangeRateSnapshot("USD", "KRW", BigDecimal("1432.6"), Instant.parse("2026-07-14T00:00:00Z"))

    @BeforeEach
    fun setUp() {
        every { executor.execute(any(), any()) } answers { secondArg<() -> JobResult>().invoke() }
        every { marketProvider.defaultMarket() } returns market()
        job = FxIngestionJob(provider, writer, cacheWriter, marketProvider, executor)
    }

    @Test
    fun `provider 결과를 DB-first 순서로 저장한다`() {
        every { provider.fetch(any(), any()) } returns snapshot

        assertThat(job.run()).isEqualTo(JobResult.Success)
        verifyOrder {
            writer.save(snapshot)
            cacheWriter.save(snapshot)
        }
    }

    @Test
    fun `DB 저장 실패 시 cache를 쓰지 않는다`() {
        every { provider.fetch(any(), any()) } returns snapshot
        every { writer.save(snapshot) } throws IllegalStateException("db")

        assertThat(job.run()).isInstanceOf(JobResult.Failure::class.java)
        verify(exactly = 0) { cacheWriter.save(any()) }
    }

    @Test
    fun `cache 저장 실패는 durable write 이후 failure다`() {
        every { provider.fetch(any(), any()) } returns snapshot
        every { cacheWriter.save(snapshot) } throws IllegalStateException("redis")

        assertThat(job.run()).isInstanceOf(JobResult.Failure::class.java)
        verify(exactly = 1) { writer.save(snapshot) }
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
