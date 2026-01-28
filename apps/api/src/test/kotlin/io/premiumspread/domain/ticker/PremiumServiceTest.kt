package io.premiumspread.domain.ticker

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.premiumspread.PremiumFixtures
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant

class PremiumServiceTest {

    private lateinit var premiumRepository: PremiumRepository
    private lateinit var service: PremiumService

    @BeforeEach
    fun setUp() {
        premiumRepository = mockk()
        service = PremiumService(premiumRepository)
    }

    @Nested
    inner class Save {

        @Test
        fun `프리미엄을 저장한다`() {
            val premium = PremiumFixtures.premium()

            every { premiumRepository.save(premium) } returns premium

            val result = service.save(premium)

            assertThat(result).isEqualTo(premium)
            verify(exactly = 1) { premiumRepository.save(premium) }
        }
    }

    @Nested
    inner class FindLatestBySymbol {

        @Test
        fun `심볼로 최신 프리미엄을 조회한다`() {
            val symbol = Symbol("BTC")
            val premium = PremiumFixtures.premium(symbol = "BTC")

            every { premiumRepository.findLatestBySymbol(symbol) } returns premium

            val result = service.findLatestBySymbol(symbol)

            assertThat(result).isEqualTo(premium)
        }

        @Test
        fun `프리미엄이 없으면 null을 반환한다`() {
            val symbol = Symbol("BTC")

            every { premiumRepository.findLatestBySymbol(symbol) } returns null

            val result = service.findLatestBySymbol(symbol)

            assertThat(result).isNull()
        }
    }

    @Nested
    inner class FindAllBySymbolAndPeriod {

        @Test
        fun `심볼과 기간으로 프리미엄 목록을 조회한다`() {
            val symbol = Symbol("BTC")
            val from = Instant.parse("2024-01-01T00:00:00Z")
            val to = Instant.parse("2024-01-02T00:00:00Z")
            val premiums = listOf(
                PremiumFixtures.premium(symbol = "BTC", id = 1L),
                PremiumFixtures.premium(symbol = "BTC", id = 2L),
            )

            every { premiumRepository.findAllBySymbolAndPeriod(symbol, from, to) } returns premiums

            val result = service.findAllBySymbolAndPeriod(symbol, from, to)

            assertThat(result).hasSize(2)
            assertThat(result[0].id).isEqualTo(1L)
            assertThat(result[1].id).isEqualTo(2L)
        }

        @Test
        fun `프리미엄이 없으면 빈 목록을 반환한다`() {
            val symbol = Symbol("BTC")
            val from = Instant.parse("2024-01-01T00:00:00Z")
            val to = Instant.parse("2024-01-02T00:00:00Z")

            every { premiumRepository.findAllBySymbolAndPeriod(symbol, from, to) } returns emptyList()

            val result = service.findAllBySymbolAndPeriod(symbol, from, to)

            assertThat(result).isEmpty()
        }
    }
}
