package io.premiumspread.application.ticker

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.premiumspread.domain.ticker.TickerService
import io.premiumspread.infrastructure.cache.CachedFxRate
import io.premiumspread.infrastructure.cache.CachedTicker
import io.premiumspread.infrastructure.cache.FxCacheReader
import io.premiumspread.infrastructure.cache.TickerCacheReader
import io.premiumspread.infrastructure.exchangerate.ExchangeRateQueryRepository
import io.premiumspread.infrastructure.exchangerate.ExchangeRateSnapshot
import io.premiumspread.infrastructure.ticker.TickerAggregationQueryRepository
import io.premiumspread.infrastructure.ticker.TickerAggregationSnapshot
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

class TickerCacheFacadeTest {

    private lateinit var tickerCacheReader: TickerCacheReader
    private lateinit var fxCacheReader: FxCacheReader
    private lateinit var tickerService: TickerService
    private lateinit var exchangeRateQueryRepository: ExchangeRateQueryRepository
    private lateinit var tickerAggregationQueryRepository: TickerAggregationQueryRepository
    private lateinit var facade: TickerCacheFacade

    @BeforeEach
    fun setUp() {
        tickerCacheReader = mockk()
        fxCacheReader = mockk()
        tickerService = mockk()
        exchangeRateQueryRepository = mockk()
        tickerAggregationQueryRepository = mockk()
        facade = TickerCacheFacade(
            tickerCacheReader = tickerCacheReader,
            fxCacheReader = fxCacheReader,
            tickerService = tickerService,
            exchangeRateQueryRepository = exchangeRateQueryRepository,
            tickerAggregationQueryRepository = tickerAggregationQueryRepository,
        )
    }

    @Nested
    inner class `Ticker 조회` {

        @Test
        fun `캐시에서 티커를 조회한다`() {
            val cachedTicker = CachedTicker(
                exchange = "bithumb",
                symbol = "btc",
                currency = "KRW",
                price = BigDecimal("129555000"),
                volume = null,
                timestamp = Instant.now(),
            )
            every { tickerCacheReader.get("bithumb", "btc") } returns cachedTicker

            val result = facade.findLatest("bithumb", "btc")

            assertThat(result).isNotNull
            assertThat(result!!.source).isEqualTo(TickerCacheResult.DataSource.CACHE)
            assertThat(result.price).isEqualTo(BigDecimal("129555000"))
            verify(exactly = 1) { tickerCacheReader.get("bithumb", "btc") }
            verify(exactly = 0) { tickerAggregationQueryRepository.findLatestMinute(any(), any()) }
        }

        @Test
        fun `캐시 미스 시 집계 테이블에서 조회한다`() {
            val aggregation = TickerAggregationSnapshot(
                exchange = "BITHUMB",
                symbol = "BTC",
                high = BigDecimal("130000000"),
                low = BigDecimal("129000000"),
                open = BigDecimal("129500000"),
                close = BigDecimal("129800000"),
                avg = BigDecimal("129600000.0000"),
                count = 60,
                observedAt = Instant.now(),
            )
            every { tickerCacheReader.get("bithumb", "btc") } returns null
            every { tickerAggregationQueryRepository.findLatestMinute("bithumb", "btc") } returns aggregation

            val result = facade.findLatest("bithumb", "btc")

            assertThat(result).isNotNull
            assertThat(result!!.source).isEqualTo(TickerCacheResult.DataSource.DATABASE)
            assertThat(result.price).isEqualTo(BigDecimal("129800000")) // close 가격
            verify(exactly = 1) { tickerCacheReader.get("bithumb", "btc") }
            verify(exactly = 1) { tickerAggregationQueryRepository.findLatestMinute("bithumb", "btc") }
        }

        @Test
        fun `캐시와 집계 모두 없으면 null 반환`() {
            every { tickerCacheReader.get("unknown", "xxx") } returns null
            every { tickerAggregationQueryRepository.findLatestMinute("unknown", "xxx") } returns null
            every { tickerService.findLatest(any(), any()) } returns null

            val result = facade.findLatest("unknown", "xxx")

            assertThat(result).isNull()
        }
    }

    @Nested
    inner class `환율 조회` {

        @Test
        fun `캐시에서 환율을 조회한다`() {
            val cachedFx = CachedFxRate(
                baseCurrency = "USD",
                quoteCurrency = "KRW",
                rate = BigDecimal("1432.6"),
                timestamp = Instant.now(),
            )
            every { fxCacheReader.get("usd", "krw") } returns cachedFx

            val result = facade.findFxRate("usd", "krw")

            assertThat(result).isNotNull
            assertThat(result!!.source).isEqualTo(FxRateCacheResult.DataSource.CACHE)
            assertThat(result.rate).isEqualTo(BigDecimal("1432.6"))
            verify(exactly = 1) { fxCacheReader.get("usd", "krw") }
            verify(exactly = 0) { exchangeRateQueryRepository.findLatest(any(), any()) }
        }

        @Test
        fun `캐시 미스 시 DB에서 환율을 조회한다`() {
            val snapshot = ExchangeRateSnapshot(
                baseCurrency = "USD",
                quoteCurrency = "KRW",
                rate = BigDecimal("1430.5"),
                observedAt = Instant.now(),
            )
            every { fxCacheReader.get("usd", "krw") } returns null
            every { exchangeRateQueryRepository.findLatest("usd", "krw") } returns snapshot

            val result = facade.findFxRate("usd", "krw")

            assertThat(result).isNotNull
            assertThat(result!!.source).isEqualTo(FxRateCacheResult.DataSource.DATABASE)
            assertThat(result.rate).isEqualTo(BigDecimal("1430.5"))
            verify(exactly = 1) { fxCacheReader.get("usd", "krw") }
            verify(exactly = 1) { exchangeRateQueryRepository.findLatest("usd", "krw") }
        }

        @Test
        fun `캐시와 DB 모두 없으면 null 반환`() {
            every { fxCacheReader.get("usd", "krw") } returns null
            every { exchangeRateQueryRepository.findLatest("usd", "krw") } returns null

            val result = facade.findFxRate("usd", "krw")

            assertThat(result).isNull()
        }
    }

    @Nested
    inner class `편의 메서드` {

        @Test
        fun `findBithumbBtc는 bithumb btc를 조회한다`() {
            val cachedTicker = CachedTicker(
                exchange = "bithumb",
                symbol = "btc",
                currency = "KRW",
                price = BigDecimal("129555000"),
                volume = null,
                timestamp = Instant.now(),
            )
            every { tickerCacheReader.get("bithumb", "btc") } returns cachedTicker

            val result = facade.findBithumbBtc()

            assertThat(result).isNotNull
            verify(exactly = 1) { tickerCacheReader.get("bithumb", "btc") }
        }

        @Test
        fun `findUsdKrwRate는 usd krw 환율을 조회한다`() {
            val cachedFx = CachedFxRate(
                baseCurrency = "USD",
                quoteCurrency = "KRW",
                rate = BigDecimal("1432.6"),
                timestamp = Instant.now(),
            )
            every { fxCacheReader.get("usd", "krw") } returns cachedFx

            val result = facade.findUsdKrwRate()

            assertThat(result).isNotNull
            verify(exactly = 1) { fxCacheReader.get("usd", "krw") }
        }
    }
}
