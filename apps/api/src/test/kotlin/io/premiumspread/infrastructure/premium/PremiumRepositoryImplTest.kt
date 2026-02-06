package io.premiumspread.infrastructure.premium

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.premiumspread.TickerFixtures
import io.premiumspread.domain.ticker.Symbol
import io.premiumspread.domain.ticker.TickerRepository
import io.premiumspread.infrastructure.cache.CachedPremium
import io.premiumspread.infrastructure.cache.PremiumCacheReader
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

class PremiumRepositoryImplTest {

    private lateinit var premiumJpaRepository: PremiumJpaRepository
    private lateinit var premiumCacheReader: PremiumCacheReader
    private lateinit var tickerRepository: TickerRepository
    private lateinit var repository: PremiumRepositoryImpl

    @BeforeEach
    fun setUp() {
        premiumJpaRepository = mockk()
        premiumCacheReader = mockk()
        tickerRepository = mockk()
        repository = PremiumRepositoryImpl(
            premiumJpaRepository = premiumJpaRepository,
            premiumCacheReader = premiumCacheReader,
            tickerRepository = tickerRepository,
        )
    }

    @Nested
    inner class FindLatestSnapshotBySymbol {

        @Test
        fun `캐시 hit이면 캐시값으로 스냅샷을 반환한다`() {
            val cached = CachedPremium(
                symbol = "BTC",
                premiumRate = BigDecimal("1.28"),
                koreaPrice = BigDecimal("129555000"),
                foreignPrice = BigDecimal("89277"),
                foreignPriceInKrw = BigDecimal("127916893.66"),
                fxRate = BigDecimal("1432.6"),
                observedAt = Instant.parse("2024-01-01T00:00:00Z"),
            )
            every { premiumCacheReader.get("BTC") } returns cached

            val result = repository.findLatestSnapshotBySymbol(Symbol("BTC"))

            assertThat(result).isNotNull
            assertThat(result!!.symbol).isEqualTo("BTC")
            assertThat(result.premiumRate).isEqualTo(BigDecimal("1.28"))
            assertThat(result.koreaPrice).isEqualTo(BigDecimal("129555000"))
            assertThat(result.foreignPrice).isEqualTo(BigDecimal("89277"))
            assertThat(result.foreignPriceInKrw).isEqualTo(BigDecimal("127916893.66"))
            assertThat(result.fxRate).isEqualTo(BigDecimal("1432.6"))
            verify(exactly = 0) { premiumJpaRepository.findLatestBySymbol(any()) }
        }

        @Test
        fun `캐시 miss + DB hit이면 ticker 가격을 조합하여 스냅샷을 반환한다`() {
            every { premiumCacheReader.get("BTC") } returns null

            val koreaTicker = TickerFixtures.koreaTicker(symbol = "BTC", price = BigDecimal("129555000"))
            val foreignTicker = TickerFixtures.foreignTicker(symbol = "BTC", price = BigDecimal("89277"))
            val fxTicker = TickerFixtures.fxTicker(price = BigDecimal("1432.6"))

            val premium = io.premiumspread.domain.premium.Premium.create(koreaTicker, foreignTicker, fxTicker)
            val idField = io.premiumspread.domain.BaseEntity::class.java.getDeclaredField("id")
            idField.isAccessible = true
            idField.set(premium, 1L)

            every { premiumJpaRepository.findLatestBySymbol("BTC") } returns premium
            every { tickerRepository.findById(koreaTicker.id) } returns koreaTicker
            every { tickerRepository.findById(foreignTicker.id) } returns foreignTicker
            every { tickerRepository.findById(fxTicker.id) } returns fxTicker

            val result = repository.findLatestSnapshotBySymbol(Symbol("BTC"))

            assertThat(result).isNotNull
            assertThat(result!!.symbol).isEqualTo("BTC")
            assertThat(result.premiumRate).isEqualByComparingTo(BigDecimal("1.30"))
            assertThat(result.koreaPrice).isEqualByComparingTo(BigDecimal("129555000"))
            assertThat(result.foreignPrice).isEqualByComparingTo(BigDecimal("89277"))
            assertThat(result.fxRate).isEqualByComparingTo(BigDecimal("1432.6"))
            assertThat(result.foreignPriceInKrw).isEqualByComparingTo(
                BigDecimal("89277").multiply(BigDecimal("1432.6")),
            )
        }

        @Test
        fun `캐시 miss + DB miss이면 null을 반환한다`() {
            every { premiumCacheReader.get("BTC") } returns null
            every { premiumJpaRepository.findLatestBySymbol("BTC") } returns null

            val result = repository.findLatestSnapshotBySymbol(Symbol("BTC"))

            assertThat(result).isNull()
        }

        @Test
        fun `캐시 miss + DB hit이지만 ticker가 없으면 null을 반환한다`() {
            every { premiumCacheReader.get("BTC") } returns null

            val koreaTicker = TickerFixtures.koreaTicker(symbol = "BTC")
            val foreignTicker = TickerFixtures.foreignTicker(symbol = "BTC")
            val fxTicker = TickerFixtures.fxTicker()

            val premium = io.premiumspread.domain.premium.Premium.create(koreaTicker, foreignTicker, fxTicker)
            val idField = io.premiumspread.domain.BaseEntity::class.java.getDeclaredField("id")
            idField.isAccessible = true
            idField.set(premium, 1L)

            every { premiumJpaRepository.findLatestBySymbol("BTC") } returns premium
            every { tickerRepository.findById(koreaTicker.id) } returns null
            every { tickerRepository.findById(foreignTicker.id) } returns foreignTicker
            every { tickerRepository.findById(fxTicker.id) } returns fxTicker

            val result = repository.findLatestSnapshotBySymbol(Symbol("BTC"))

            assertThat(result).isNull()
        }
    }
}
