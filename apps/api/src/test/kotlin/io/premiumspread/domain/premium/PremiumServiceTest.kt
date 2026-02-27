package io.premiumspread.domain.premium

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.premiumspread.PremiumFixtures
import io.premiumspread.TickerFixtures
import io.premiumspread.domain.ticker.Symbol
import io.premiumspread.withId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.temporal.ChronoUnit

class PremiumServiceTest {

    private lateinit var premiumRepository: PremiumRepository
    private lateinit var service: PremiumService

    @BeforeEach
    fun setUp() {
        premiumRepository = mockk()
        service = PremiumService(premiumRepository)
    }

    @Nested
    inner class Create {

        @Test
        fun `Command로 프리미엄을 생성한다`() {
            val koreaTicker = TickerFixtures.koreaTicker(symbol = "BTC", price = BigDecimal("129555000"))
            val foreignTicker = TickerFixtures.foreignTicker(symbol = "BTC", price = BigDecimal("89277"))
            val fxTicker = TickerFixtures.fxTicker(price = BigDecimal("1432.6"))

            val command = PremiumCommand.Create(
                koreaTicker = koreaTicker,
                foreignTicker = foreignTicker,
                fxTicker = fxTicker,
            )

            val premiumSlot = slot<Premium>()
            every { premiumRepository.save(capture(premiumSlot)) } answers {
                premiumSlot.captured.withId(1L)
            }

            val result = service.create(command)

            assertThat(result.id).isEqualTo(1L)
            assertThat(result.symbol.code).isEqualTo("BTC")
            assertThat(result.koreaTickerId).isEqualTo(koreaTicker.id)
            assertThat(result.foreignTickerId).isEqualTo(foreignTicker.id)
            assertThat(result.fxTickerId).isEqualTo(fxTicker.id)
            assertThat(result.premiumRate).isEqualByComparingTo(BigDecimal("1.30"))

            verify(exactly = 1) { premiumRepository.save(any()) }
        }
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
    inner class FindLatestSnapshotBySymbol {

        @Test
        fun `심볼로 최신 프리미엄 스냅샷을 조회한다`() {
            val symbol = Symbol("BTC")
            val snapshot = PremiumSnapshot(
                symbol = "BTC",
                premiumRate = BigDecimal("1.30"),
                koreaPrice = BigDecimal("129555000"),
                foreignPrice = BigDecimal("89277"),
                foreignPriceInKrw = BigDecimal("127916893.2"),
                fxRate = BigDecimal("1432.6"),
                observedAt = Instant.parse("2024-01-01T00:00:00Z"),
            )

            every { premiumRepository.findLatestSnapshotBySymbol(symbol) } returns snapshot

            val result = service.findLatestSnapshotBySymbol(symbol)

            assertThat(result).isEqualTo(snapshot)
        }

        @Test
        fun `스냅샷이 없으면 null을 반환한다`() {
            val symbol = Symbol("BTC")

            every { premiumRepository.findLatestSnapshotBySymbol(symbol) } returns null

            val result = service.findLatestSnapshotBySymbol(symbol)

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

    @Nested
    inner class FindAggregation {

        @Test
        fun `심볼, 인터벌, 기간으로 집계 데이터를 조회한다`() {
            val symbol = Symbol("BTC")
            val from = Instant.parse("2024-01-01T00:00:00Z")
            val to = Instant.parse("2024-01-02T00:00:00Z")
            val snapshots = listOf(
                PremiumAggregationSnapshot(
                    symbol = "BTC",
                    high = BigDecimal("2.50"), low = BigDecimal("1.00"),
                    open = BigDecimal("1.50"), close = BigDecimal("2.00"),
                    avg = BigDecimal("1.75"), count = 60,
                    observedAt = from,
                ),
                PremiumAggregationSnapshot(
                    symbol = "BTC",
                    high = BigDecimal("3.00"), low = BigDecimal("1.50"),
                    open = BigDecimal("2.00"), close = BigDecimal("2.50"),
                    avg = BigDecimal("2.25"), count = 55,
                    observedAt = from.plus(1, ChronoUnit.HOURS),
                ),
            )

            every { premiumRepository.findAggregation(symbol, "1h", from, to) } returns snapshots

            val result = service.findAggregation(symbol, "1h", from, to)

            assertThat(result).hasSize(2)
            assertThat(result[0].high).isEqualByComparingTo(BigDecimal("2.50"))
            assertThat(result[1].avg).isEqualByComparingTo(BigDecimal("2.25"))
        }

        @Test
        fun `집계 데이터가 없으면 빈 목록을 반환한다`() {
            val symbol = Symbol("BTC")
            val from = Instant.parse("2024-01-01T00:00:00Z")
            val to = Instant.parse("2024-01-02T00:00:00Z")

            every { premiumRepository.findAggregation(symbol, "1m", from, to) } returns emptyList()

            val result = service.findAggregation(symbol, "1m", from, to)

            assertThat(result).isEmpty()
        }
    }
}
