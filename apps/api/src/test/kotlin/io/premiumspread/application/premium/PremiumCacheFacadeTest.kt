package io.premiumspread.application.premium

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.premiumspread.domain.premium.Premium
import io.premiumspread.domain.premium.PremiumService
import io.premiumspread.domain.ticker.Symbol
import io.premiumspread.infrastructure.cache.CachedPremium
import io.premiumspread.infrastructure.cache.PremiumCacheReader
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

class PremiumCacheFacadeTest {

    private lateinit var premiumCacheReader: PremiumCacheReader
    private lateinit var premiumService: PremiumService
    private lateinit var facade: PremiumCacheFacade

    @BeforeEach
    fun setUp() {
        premiumCacheReader = mockk()
        premiumService = mockk()
        facade = PremiumCacheFacade(
            premiumCacheReader = premiumCacheReader,
            premiumService = premiumService,
        )
    }

    @Nested
    inner class `Premium 조회` {

        @Test
        fun `캐시에서 프리미엄을 조회한다`() {
            val cachedPremium = CachedPremium(
                symbol = "BTC",
                premiumRate = BigDecimal("1.28"),
                koreaPrice = BigDecimal("129555000"),
                foreignPrice = BigDecimal("89277"),
                foreignPriceInKrw = BigDecimal("127916893.66"),
                fxRate = BigDecimal("1432.6"),
                observedAt = Instant.now(),
            )
            every { premiumCacheReader.get("btc") } returns cachedPremium

            val result = facade.findLatest("btc")

            assertThat(result).isNotNull
            assertThat(result!!.source).isEqualTo(PremiumCacheResult.DataSource.CACHE)
            assertThat(result.premiumRate).isEqualTo(BigDecimal("1.28"))
            verify(exactly = 1) { premiumCacheReader.get("btc") }
            verify(exactly = 0) { premiumService.findLatestBySymbol(any()) }
        }

        @Test
        fun `캐시 미스 시 DB에서 프리미엄을 조회한다`() {
            val premium = mockk<Premium>()
            every { premium.symbol } returns Symbol("BTC")
            every { premium.premiumRate } returns BigDecimal("1.25")
            every { premium.observedAt } returns Instant.now()

            every { premiumCacheReader.get("btc") } returns null
            every { premiumService.findLatestBySymbol(Symbol("btc")) } returns premium

            val result = facade.findLatest("btc")

            assertThat(result).isNotNull
            assertThat(result!!.source).isEqualTo(PremiumCacheResult.DataSource.DATABASE)
            assertThat(result.premiumRate).isEqualTo(BigDecimal("1.25"))
            verify(exactly = 1) { premiumCacheReader.get("btc") }
            verify(exactly = 1) { premiumService.findLatestBySymbol(Symbol("btc")) }
        }

        @Test
        fun `캐시와 DB 모두 없으면 null 반환`() {
            every { premiumCacheReader.get("btc") } returns null
            every { premiumService.findLatestBySymbol(Symbol("btc")) } returns null

            val result = facade.findLatest("btc")

            assertThat(result).isNull()
        }
    }

    @Nested
    inner class `편의 메서드` {

        @Test
        fun `findBtcLatest는 btc 프리미엄을 조회한다`() {
            val cachedPremium = CachedPremium(
                symbol = "BTC",
                premiumRate = BigDecimal("1.28"),
                koreaPrice = BigDecimal("129555000"),
                foreignPrice = BigDecimal("89277"),
                foreignPriceInKrw = BigDecimal("127916893.66"),
                fxRate = BigDecimal("1432.6"),
                observedAt = Instant.now(),
            )
            every { premiumCacheReader.get("btc") } returns cachedPremium

            val result = facade.findBtcLatest()

            assertThat(result).isNotNull
            verify(exactly = 1) { premiumCacheReader.get("btc") }
        }

        @Test
        fun `isCached는 캐시 존재 여부를 반환한다`() {
            every { premiumCacheReader.exists("btc") } returns true

            val result = facade.isCached("btc")

            assertThat(result).isTrue()
            verify(exactly = 1) { premiumCacheReader.exists("btc") }
        }
    }
}
