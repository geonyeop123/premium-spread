package io.premiumspread.application.ticker

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.premiumspread.withId
import io.premiumspread.domain.ticker.Currency
import io.premiumspread.domain.ticker.Exchange
import io.premiumspread.domain.ticker.ExchangeRegion
import io.premiumspread.domain.ticker.Quote
import io.premiumspread.domain.ticker.QuoteBaseType
import io.premiumspread.domain.ticker.Symbol
import io.premiumspread.domain.ticker.Ticker
import io.premiumspread.domain.ticker.TickerRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

class TickerIngestFacadeTest {

    private lateinit var tickerRepository: TickerRepository
    private lateinit var facade: TickerIngestFacade

    @BeforeEach
    fun setUp() {
        tickerRepository = mockk()
        facade = TickerIngestFacade(tickerRepository)
    }

    @Test
    fun `코인 티커를 저장한다`() {
        val criteria = TickerIngestCriteria(
            exchange = Exchange.UPBIT,
            baseCode = "BTC",
            quoteCurrency = Currency.KRW,
            price = BigDecimal("129555000"),
            observedAt = Instant.parse("2024-01-01T00:00:00Z"),
        )

        val tickerSlot = slot<Ticker>()
        every { tickerRepository.save(capture(tickerSlot)) } answers {
            tickerSlot.captured.withId(1L)
        }

        val result = facade.ingest(criteria)

        assertThat(result.id).isEqualTo(1L)
        assertThat(result.exchange).isEqualTo(Exchange.UPBIT)
        assertThat(result.exchangeRegion).isEqualTo(ExchangeRegion.KOREA)
        assertThat(result.baseCode).isEqualTo("BTC")
        assertThat(result.quoteCurrency).isEqualTo(Currency.KRW)
        assertThat(result.price).isEqualByComparingTo(BigDecimal("129555000"))
        assertThat(result.observedAt).isEqualTo(Instant.parse("2024-01-01T00:00:00Z"))

        verify(exactly = 1) { tickerRepository.save(any()) }
        assertThat(tickerSlot.captured.quote.baseType).isEqualTo(QuoteBaseType.SYMBOL)
    }

    @Test
    fun `환율 티커를 저장한다`() {
        val criteria = TickerIngestCriteria(
            exchange = Exchange.FX_PROVIDER,
            baseCode = "USD",
            quoteCurrency = Currency.KRW,
            price = BigDecimal("1432.6"),
            observedAt = Instant.parse("2024-01-01T00:00:00Z"),
        )

        val tickerSlot = slot<Ticker>()
        every { tickerRepository.save(capture(tickerSlot)) } answers {
            tickerSlot.captured.withId(2L)
        }

        val result = facade.ingest(criteria)

        assertThat(result.id).isEqualTo(2L)
        assertThat(result.exchange).isEqualTo(Exchange.FX_PROVIDER)
        assertThat(result.baseCode).isEqualTo("USD")
        assertThat(result.quoteCurrency).isEqualTo(Currency.KRW)
        assertThat(result.price).isEqualByComparingTo(BigDecimal("1432.6"))

        verify(exactly = 1) { tickerRepository.save(any()) }
        assertThat(tickerSlot.captured.quote.baseType).isEqualTo(QuoteBaseType.CURRENCY)
    }

    @Test
    fun `해외 거래소 코인 티커를 저장한다`() {
        val criteria = TickerIngestCriteria(
            exchange = Exchange.BINANCE,
            baseCode = "BTC",
            quoteCurrency = Currency.USD,
            price = BigDecimal("89277"),
            observedAt = Instant.parse("2024-01-01T00:00:00Z"),
        )

        val tickerSlot = slot<Ticker>()
        every { tickerRepository.save(capture(tickerSlot)) } answers {
            tickerSlot.captured.withId(3L)
        }

        val result = facade.ingest(criteria)

        assertThat(result.id).isEqualTo(3L)
        assertThat(result.exchange).isEqualTo(Exchange.BINANCE)
        assertThat(result.exchangeRegion).isEqualTo(ExchangeRegion.FOREIGN)
        assertThat(result.baseCode).isEqualTo("BTC")
        assertThat(result.quoteCurrency).isEqualTo(Currency.USD)
    }
}
