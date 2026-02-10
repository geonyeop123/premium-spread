package io.premiumspread.infrastructure.ticker

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.premiumspread.domain.ticker.Currency
import io.premiumspread.domain.ticker.Exchange
import io.premiumspread.infrastructure.cache.CachedTicker
import io.premiumspread.infrastructure.cache.TickerCacheReader
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

class TickerRepositoryImplTest {

    private lateinit var tickerJpaRepository: TickerJpaRepository
    private lateinit var tickerCacheReader: TickerCacheReader
    private lateinit var tickerAggregationQueryRepository: TickerAggregationQueryRepository
    private lateinit var repository: TickerRepositoryImpl

    @BeforeEach
    fun setUp() {
        tickerJpaRepository = mockk()
        tickerCacheReader = mockk()
        tickerAggregationQueryRepository = mockk()
        repository = TickerRepositoryImpl(
            tickerJpaRepository = tickerJpaRepository,
            tickerCacheReader = tickerCacheReader,
            tickerAggregationQueryRepository = tickerAggregationQueryRepository,
        )
    }

    @Nested
    inner class FindLatestSnapshotByExchangeAndSymbol {

        @Test
        fun `캐시 hit이면 캐시값으로 스냅샷을 반환한다`() {
            val cached = CachedTicker(
                exchange = "BITHUMB",
                symbol = "BTC",
                currency = "KRW",
                price = BigDecimal("129555000"),
                volume = BigDecimal("10.5"),
                timestamp = Instant.parse("2024-01-01T00:00:00Z"),
            )
            every { tickerCacheReader.get("bithumb", "btc") } returns cached

            val result = repository.findLatestSnapshotByExchangeAndSymbol("bithumb", "btc")

            assertThat(result).isNotNull
            assertThat(result!!.exchange).isEqualTo("BITHUMB")
            assertThat(result.symbol).isEqualTo("BTC")
            assertThat(result.currency).isEqualTo("KRW")
            assertThat(result.price).isEqualByComparingTo(BigDecimal("129555000"))
            assertThat(result.volume).isEqualByComparingTo(BigDecimal("10.5"))
            verify(exactly = 0) { tickerAggregationQueryRepository.findLatestMinute(any(), any()) }
            verify(exactly = 0) { tickerJpaRepository.findLatest(any(), any(), any()) }
        }

        @Test
        fun `캐시 miss + aggregation hit이면 close를 price로 사용하여 스냅샷을 반환한다`() {
            every { tickerCacheReader.get("bithumb", "btc") } returns null

            val aggregation = TickerAggregationSnapshot(
                exchange = "BITHUMB",
                symbol = "BTC",
                high = BigDecimal("130000000"),
                low = BigDecimal("128000000"),
                open = BigDecimal("129000000"),
                close = BigDecimal("129500000"),
                avg = BigDecimal("129200000"),
                count = 60,
                observedAt = Instant.parse("2024-01-01T00:00:00Z"),
            )
            every { tickerAggregationQueryRepository.findLatestMinute("bithumb", "btc") } returns aggregation

            val result = repository.findLatestSnapshotByExchangeAndSymbol("bithumb", "btc")

            assertThat(result).isNotNull
            assertThat(result!!.exchange).isEqualTo("BITHUMB")
            assertThat(result.symbol).isEqualTo("BTC")
            assertThat(result.price).isEqualByComparingTo(BigDecimal("129500000"))
            assertThat(result.volume).isNull()
            verify(exactly = 0) { tickerJpaRepository.findLatest(any(), any(), any()) }
        }

        @Test
        fun `캐시 miss + aggregation miss + DB hit이면 ticker에서 스냅샷을 반환한다`() {
            every { tickerCacheReader.get("bithumb", "btc") } returns null
            every { tickerAggregationQueryRepository.findLatestMinute("bithumb", "btc") } returns null

            val ticker = io.premiumspread.domain.ticker.Ticker.create(
                exchange = Exchange.BITHUMB,
                quote = io.premiumspread.domain.ticker.Quote.coin(
                    io.premiumspread.domain.ticker.Symbol("BTC"),
                    Currency.KRW,
                ),
                price = BigDecimal("129555000"),
                observedAt = Instant.parse("2024-01-01T00:00:00Z"),
            )
            every {
                tickerJpaRepository.findLatest(Exchange.BITHUMB, "BTC", Currency.KRW)
            } returns ticker

            val result = repository.findLatestSnapshotByExchangeAndSymbol("bithumb", "btc")

            assertThat(result).isNotNull
            assertThat(result!!.exchange).isEqualTo("BITHUMB")
            assertThat(result.symbol).isEqualTo("BTC")
            assertThat(result.currency).isEqualTo("KRW")
            assertThat(result.price).isEqualByComparingTo(BigDecimal("129555000"))
        }

        @Test
        fun `모든 소스에서 조회 실패하면 null을 반환한다`() {
            every { tickerCacheReader.get("bithumb", "btc") } returns null
            every { tickerAggregationQueryRepository.findLatestMinute("bithumb", "btc") } returns null
            every {
                tickerJpaRepository.findLatest(Exchange.BITHUMB, "BTC", Currency.KRW)
            } returns null

            val result = repository.findLatestSnapshotByExchangeAndSymbol("bithumb", "btc")

            assertThat(result).isNull()
        }

        @Test
        fun `알 수 없는 exchange이면 null을 반환한다`() {
            every { tickerCacheReader.get("unknown", "btc") } returns null
            every { tickerAggregationQueryRepository.findLatestMinute("unknown", "btc") } returns null

            val result = repository.findLatestSnapshotByExchangeAndSymbol("unknown", "btc")

            assertThat(result).isNull()
        }
    }
}
