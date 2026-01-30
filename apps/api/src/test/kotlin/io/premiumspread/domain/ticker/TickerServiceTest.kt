package io.premiumspread.domain.ticker

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.premiumspread.TickerFixtures
import io.premiumspread.withId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

class TickerServiceTest {

    private lateinit var tickerRepository: TickerRepository
    private lateinit var service: TickerService

    @BeforeEach
    fun setUp() {
        tickerRepository = mockk()
        service = TickerService(tickerRepository)
    }

    @Nested
    inner class Create {

        @Test
        fun `코인 티커를 생성한다`() {
            val command = TickerCommand.Create(
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

            val result = service.create(command)

            assertThat(result.id).isEqualTo(1L)
            assertThat(result.exchange).isEqualTo(Exchange.UPBIT)
            assertThat(result.quote.baseCode).isEqualTo("BTC")
            assertThat(result.quote.baseType).isEqualTo(QuoteBaseType.SYMBOL)
            assertThat(result.quote.currency).isEqualTo(Currency.KRW)
            assertThat(result.price).isEqualByComparingTo(BigDecimal("129555000"))

            verify(exactly = 1) { tickerRepository.save(any()) }
        }

        @Test
        fun `환율 티커를 생성한다`() {
            val command = TickerCommand.Create(
                exchange = Exchange.FX_PROVIDER,
                baseCode = "USD",
                quoteCurrency = Currency.KRW,
                price = BigDecimal("1432.6"),
                observedAt = Instant.parse("2024-01-01T00:00:00Z"),
            )

            val tickerSlot = slot<Ticker>()
            every { tickerRepository.save(capture(tickerSlot)) } answers {
                tickerSlot.captured.withId(1L)
            }

            val result = service.create(command)

            assertThat(result.quote.baseCode).isEqualTo("USD")
            assertThat(result.quote.baseType).isEqualTo(QuoteBaseType.CURRENCY)
            assertThat(result.quote.currency).isEqualTo(Currency.KRW)

            verify(exactly = 1) { tickerRepository.save(any()) }
        }
    }

    @Nested
    inner class Save {

        @Test
        fun `티커를 저장한다`() {
            val ticker = TickerFixtures.koreaTicker()

            every { tickerRepository.save(ticker) } returns ticker

            val result = service.save(ticker)

            assertThat(result).isEqualTo(ticker)
            verify(exactly = 1) { tickerRepository.save(ticker) }
        }
    }

    @Nested
    inner class FindLatest {

        @Test
        fun `거래소와 Quote로 최신 티커를 조회한다`() {
            val ticker = TickerFixtures.koreaTicker()
            val exchange = Exchange.UPBIT
            val quote = Quote.coin(Symbol("BTC"), Currency.KRW)

            every { tickerRepository.findLatest(exchange, quote) } returns ticker

            val result = service.findLatest(exchange, quote)

            assertThat(result).isEqualTo(ticker)
        }

        @Test
        fun `티커가 없으면 null을 반환한다`() {
            val exchange = Exchange.UPBIT
            val quote = Quote.coin(Symbol("BTC"), Currency.KRW)

            every { tickerRepository.findLatest(exchange, quote) } returns null

            val result = service.findLatest(exchange, quote)

            assertThat(result).isNull()
        }
    }

    @Nested
    inner class FindById {

        @Test
        fun `ID로 티커를 조회한다`() {
            val ticker = TickerFixtures.koreaTicker(id = 1L)

            every { tickerRepository.findById(1L) } returns ticker

            val result = service.findById(1L)

            assertThat(result).isEqualTo(ticker)
        }

        @Test
        fun `티커가 없으면 null을 반환한다`() {
            every { tickerRepository.findById(999L) } returns null

            val result = service.findById(999L)

            assertThat(result).isNull()
        }
    }
}
