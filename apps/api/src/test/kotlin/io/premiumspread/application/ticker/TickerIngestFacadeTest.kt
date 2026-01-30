package io.premiumspread.application.ticker

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.premiumspread.TickerFixtures
import io.premiumspread.domain.ticker.Currency
import io.premiumspread.domain.ticker.Exchange
import io.premiumspread.domain.ticker.ExchangeRegion
import io.premiumspread.domain.ticker.TickerCommand
import io.premiumspread.domain.ticker.TickerService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

class TickerIngestFacadeTest {

    private lateinit var tickerService: TickerService
    private lateinit var facade: TickerIngestFacade

    @BeforeEach
    fun setUp() {
        tickerService = mockk()
        facade = TickerIngestFacade(tickerService)
    }

    @Test
    fun `코인 티커를 저장한다`() {
        val criteria = TickerCriteria.Ingest(
            exchange = Exchange.UPBIT,
            baseCode = "BTC",
            quoteCurrency = Currency.KRW,
            price = BigDecimal("129555000"),
            observedAt = Instant.parse("2024-01-01T00:00:00Z"),
        )

        val commandSlot = slot<TickerCommand.Create>()
        every { tickerService.create(capture(commandSlot)) } returns
            TickerFixtures.koreaTicker(id = 1L)

        val result = facade.ingest(criteria)

        assertThat(result.id).isEqualTo(1L)
        assertThat(result.exchange).isEqualTo(Exchange.UPBIT)
        assertThat(result.exchangeRegion).isEqualTo(ExchangeRegion.KOREA)
        assertThat(result.baseCode).isEqualTo("BTC")
        assertThat(result.quoteCurrency).isEqualTo(Currency.KRW)

        verify(exactly = 1) { tickerService.create(any()) }
        assertThat(commandSlot.captured.exchange).isEqualTo(Exchange.UPBIT)
        assertThat(commandSlot.captured.baseCode).isEqualTo("BTC")
        assertThat(commandSlot.captured.quoteCurrency).isEqualTo(Currency.KRW)
    }

    @Test
    fun `환율 티커를 저장한다`() {
        val criteria = TickerCriteria.Ingest(
            exchange = Exchange.FX_PROVIDER,
            baseCode = "USD",
            quoteCurrency = Currency.KRW,
            price = BigDecimal("1432.6"),
            observedAt = Instant.parse("2024-01-01T00:00:00Z"),
        )

        val commandSlot = slot<TickerCommand.Create>()
        every { tickerService.create(capture(commandSlot)) } returns
            TickerFixtures.fxTicker(id = 2L)

        val result = facade.ingest(criteria)

        assertThat(result.id).isEqualTo(2L)
        assertThat(result.exchange).isEqualTo(Exchange.FX_PROVIDER)
        assertThat(result.baseCode).isEqualTo("USD")
        assertThat(result.quoteCurrency).isEqualTo(Currency.KRW)

        verify(exactly = 1) { tickerService.create(any()) }
        assertThat(commandSlot.captured.baseCode).isEqualTo("USD")
    }

    @Test
    fun `해외 거래소 코인 티커를 저장한다`() {
        val criteria = TickerCriteria.Ingest(
            exchange = Exchange.BINANCE,
            baseCode = "BTC",
            quoteCurrency = Currency.USD,
            price = BigDecimal("89277"),
            observedAt = Instant.parse("2024-01-01T00:00:00Z"),
        )

        val commandSlot = slot<TickerCommand.Create>()
        every { tickerService.create(capture(commandSlot)) } returns
            TickerFixtures.foreignTicker(id = 3L)

        val result = facade.ingest(criteria)

        assertThat(result.id).isEqualTo(3L)
        assertThat(result.exchange).isEqualTo(Exchange.BINANCE)
        assertThat(result.exchangeRegion).isEqualTo(ExchangeRegion.FOREIGN)
        assertThat(result.baseCode).isEqualTo("BTC")
        assertThat(result.quoteCurrency).isEqualTo(Currency.USD)

        verify(exactly = 1) { tickerService.create(any()) }
    }
}
