package io.premiumspread.domain.ticker

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.premiumspread.TickerFixtures
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class TickerServiceTest {

    private lateinit var tickerRepository: TickerRepository
    private lateinit var service: TickerService

    @BeforeEach
    fun setUp() {
        tickerRepository = mockk()
        service = TickerService(tickerRepository)
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
