package io.premiumspread.application.ticker

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.premiumspread.PremiumFixtures
import io.premiumspread.TickerFixtures
import io.premiumspread.domain.ticker.Currency
import io.premiumspread.domain.ticker.Exchange
import io.premiumspread.domain.ticker.Premium
import io.premiumspread.domain.ticker.PremiumRepository
import io.premiumspread.domain.ticker.Quote
import io.premiumspread.domain.ticker.Symbol
import io.premiumspread.domain.ticker.TickerRepository
import io.premiumspread.withId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

class PremiumFacadeTest {

    private lateinit var tickerRepository: TickerRepository
    private lateinit var premiumRepository: PremiumRepository
    private lateinit var facade: PremiumFacade

    @BeforeEach
    fun setUp() {
        tickerRepository = mockk()
        premiumRepository = mockk()
        facade = PremiumFacade(tickerRepository, premiumRepository)
    }

    @Nested
    inner class CalculateAndSave {

        @Test
        fun `프리미엄을 계산하고 저장한다`() {
            val koreaTicker = TickerFixtures.koreaTicker(symbol = "BTC", price = BigDecimal("129555000"))
            val foreignTicker = TickerFixtures.foreignTicker(symbol = "BTC", price = BigDecimal("89277"))
            val fxTicker = TickerFixtures.fxTicker(price = BigDecimal("1432.6"))

            every {
                tickerRepository.findLatest(Exchange.UPBIT, Quote.coin(Symbol("BTC"), Currency.KRW))
            } returns koreaTicker

            every {
                tickerRepository.findLatest(Exchange.BINANCE, Quote.coin(Symbol("BTC"), Currency.USD))
            } returns foreignTicker

            every {
                tickerRepository.findLatest(Exchange.FX_PROVIDER, Quote.fx(Currency.USD, Currency.KRW))
            } returns fxTicker

            val premiumSlot = slot<Premium>()
            every { premiumRepository.save(capture(premiumSlot)) } answers {
                premiumSlot.captured.withId(1L)
            }

            val result = facade.calculateAndSave(PremiumCreateCriteria(symbol = "BTC"))

            assertThat(result.id).isEqualTo(1L)
            assertThat(result.symbol).isEqualTo("BTC")
            assertThat(result.koreaTickerId).isEqualTo(koreaTicker.id)
            assertThat(result.foreignTickerId).isEqualTo(foreignTicker.id)
            assertThat(result.fxTickerId).isEqualTo(fxTicker.id)
            assertThat(result.premiumRate).isEqualByComparingTo(BigDecimal("1.30"))

            verify(exactly = 1) { premiumRepository.save(any()) }
        }

        @Test
        fun `한국 티커가 없으면 예외를 던진다`() {
            every {
                tickerRepository.findLatest(Exchange.UPBIT, Quote.coin(Symbol("BTC"), Currency.KRW))
            } returns null

            assertThatThrownBy {
                facade.calculateAndSave(PremiumCreateCriteria(symbol = "BTC"))
            }.isInstanceOf(TickerNotFoundException::class.java)
                .hasMessageContaining("Korea ticker not found")
        }

        @Test
        fun `해외 티커가 없으면 예외를 던진다`() {
            val koreaTicker = TickerFixtures.koreaTicker()

            every {
                tickerRepository.findLatest(Exchange.UPBIT, Quote.coin(Symbol("BTC"), Currency.KRW))
            } returns koreaTicker

            every {
                tickerRepository.findLatest(Exchange.BINANCE, Quote.coin(Symbol("BTC"), Currency.USD))
            } returns null

            assertThatThrownBy {
                facade.calculateAndSave(PremiumCreateCriteria(symbol = "BTC"))
            }.isInstanceOf(TickerNotFoundException::class.java)
                .hasMessageContaining("Foreign ticker not found")
        }

        @Test
        fun `환율 티커가 없으면 예외를 던진다`() {
            val koreaTicker = TickerFixtures.koreaTicker()
            val foreignTicker = TickerFixtures.foreignTicker()

            every {
                tickerRepository.findLatest(Exchange.UPBIT, Quote.coin(Symbol("BTC"), Currency.KRW))
            } returns koreaTicker

            every {
                tickerRepository.findLatest(Exchange.BINANCE, Quote.coin(Symbol("BTC"), Currency.USD))
            } returns foreignTicker

            every {
                tickerRepository.findLatest(Exchange.FX_PROVIDER, Quote.fx(Currency.USD, Currency.KRW))
            } returns null

            assertThatThrownBy {
                facade.calculateAndSave(PremiumCreateCriteria(symbol = "BTC"))
            }.isInstanceOf(TickerNotFoundException::class.java)
                .hasMessageContaining("FX ticker not found")
        }
    }

    @Nested
    inner class FindLatest {

        @Test
        fun `심볼로 최신 프리미엄을 조회한다`() {
            val premium = PremiumFixtures.premium(symbol = "BTC")

            every { premiumRepository.findLatestBySymbol(Symbol("BTC")) } returns premium

            val result = facade.findLatest("BTC")

            assertThat(result).isNotNull
            assertThat(result!!.symbol).isEqualTo("BTC")
            assertThat(result.id).isEqualTo(premium.id)
        }

        @Test
        fun `프리미엄이 없으면 null을 반환한다`() {
            every { premiumRepository.findLatestBySymbol(Symbol("BTC")) } returns null

            val result = facade.findLatest("BTC")

            assertThat(result).isNull()
        }
    }

    @Nested
    inner class FindByPeriod {

        @Test
        fun `기간으로 프리미엄 목록을 조회한다`() {
            val from = Instant.parse("2024-01-01T00:00:00Z")
            val to = Instant.parse("2024-01-02T00:00:00Z")
            val premiums = listOf(
                PremiumFixtures.premium(symbol = "BTC", id = 1L),
                PremiumFixtures.premium(symbol = "BTC", id = 2L),
            )

            every {
                premiumRepository.findAllBySymbolAndPeriod(Symbol("BTC"), from, to)
            } returns premiums

            val result = facade.findByPeriod("BTC", from, to)

            assertThat(result).hasSize(2)
            assertThat(result[0].id).isEqualTo(1L)
            assertThat(result[1].id).isEqualTo(2L)
        }

        @Test
        fun `프리미엄이 없으면 빈 목록을 반환한다`() {
            val from = Instant.parse("2024-01-01T00:00:00Z")
            val to = Instant.parse("2024-01-02T00:00:00Z")

            every {
                premiumRepository.findAllBySymbolAndPeriod(Symbol("BTC"), from, to)
            } returns emptyList()

            val result = facade.findByPeriod("BTC", from, to)

            assertThat(result).isEmpty()
        }
    }
}
