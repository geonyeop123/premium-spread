package io.premiumspread.infrastructure.premium

import io.premiumspread.infrastructure.common.cache.premium.PremiumAggregationCacheReader
import io.premiumspread.infrastructure.common.cache.premium.CachedPremium
import io.premiumspread.infrastructure.common.cache.premium.PremiumCacheReader
import io.premiumspread.infrastructure.common.persistence.jdbc.premium.PremiumAggregationQueryRepository
import io.premiumspread.infrastructure.common.persistence.jpa.premium.JpaPremiumRepositoryAdapter
import io.premiumspread.infrastructure.common.persistence.jpa.premium.PremiumSnapshotRow
import io.premiumspread.infrastructure.common.persistence.jpa.premium.SpringDataPremiumRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.premiumspread.domain.premium.PremiumAggregationSnapshot
import io.premiumspread.domain.market.MarketPair
import io.premiumspread.domain.ticker.Exchange
import io.premiumspread.domain.ticker.Symbol
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

class PremiumRepositoryImplTest {

    private val defaultPair = MarketPair.default(Symbol("BTC"))

    private lateinit var premiumJpaRepository: SpringDataPremiumRepository
    private lateinit var premiumCacheReader: PremiumCacheReader
    private lateinit var premiumAggregationCacheReader: PremiumAggregationCacheReader
    private lateinit var premiumAggregationQueryRepository: PremiumAggregationQueryRepository
    private lateinit var repository: JpaPremiumRepositoryAdapter

    @BeforeEach
    fun setUp() {
        premiumJpaRepository = mockk()
        premiumCacheReader = mockk()
        premiumAggregationCacheReader = mockk()
        premiumAggregationQueryRepository = mockk()
        repository = JpaPremiumRepositoryAdapter(
            premiumRepository = premiumJpaRepository,
            premiumCacheReader = premiumCacheReader,
            premiumAggregationCacheReader = premiumAggregationCacheReader,
            premiumAggregationQueryRepository = premiumAggregationQueryRepository,
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
            every { premiumCacheReader.get(defaultPair) } returns cached

            val result = repository.findLatestSnapshotByPair(defaultPair)

            assertThat(result).isNotNull
            assertThat(result!!.symbol).isEqualTo("BTC")
            assertThat(result.premiumRate).isEqualTo(BigDecimal("1.28"))
            assertThat(result.koreaPrice).isEqualTo(BigDecimal("129555000"))
            assertThat(result.foreignPrice).isEqualTo(BigDecimal("89277"))
            assertThat(result.foreignPriceInKrw).isEqualTo(BigDecimal("127916893.66"))
            assertThat(result.fxRate).isEqualTo(BigDecimal("1432.6"))
            verify(exactly = 0) { premiumJpaRepository.findLatestSnapshotByPair(any(), any(), any()) }
        }

        @Test
        fun `캐시 miss + DB JOIN 쿼리 결과가 있으면 스냅샷을 반환한다`() {
            every { premiumCacheReader.get(defaultPair) } returns null

            every {
                premiumJpaRepository.findLatestSnapshotByPair("BTC", Exchange.BITHUMB, Exchange.BINANCE)
            } returns snapshotRow()

            val result = repository.findLatestSnapshotByPair(defaultPair)

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
            every { premiumCacheReader.get(defaultPair) } returns null
            every {
                premiumJpaRepository.findLatestSnapshotByPair("BTC", Exchange.BITHUMB, Exchange.BINANCE)
            } returns null

            val result = repository.findLatestSnapshotByPair(defaultPair)

            assertThat(result).isNull()
        }

        @Test
        fun `명시적 MarketPair cache miss는 같은 거래소 pair만 DB에서 조회한다`() {
            val pair = MarketPair(Symbol("BTC"), Exchange.UPBIT, Exchange.BINANCE)
            every { premiumCacheReader.get(pair) } returns null
            every {
                premiumJpaRepository.findLatestSnapshotByPair("BTC", Exchange.UPBIT, Exchange.BINANCE)
            } returns snapshotRow(koreaExchange = Exchange.UPBIT)

            val result = repository.findLatestSnapshotByPair(pair)

            assertThat(result!!.pair).isEqualTo(pair)
            verify(exactly = 1) {
                premiumJpaRepository.findLatestSnapshotByPair("BTC", Exchange.UPBIT, Exchange.BINANCE)
            }
        }

        @Test
        fun `metadata가 있는 캐시는 요청 pair와 FX 관측정보를 정확히 보존한다`() {
            val pair = MarketPair(Symbol("BTC"), Exchange.UPBIT, Exchange.BINANCE)
            val fxObservedAt = Instant.parse("2023-12-31T23:59:00Z")
            every { premiumCacheReader.get(pair) } returns CachedPremium(
                symbol = "BTC",
                premiumRate = BigDecimal("1.2349"),
                koreaPrice = BigDecimal("129555000"),
                foreignPrice = BigDecimal("89277"),
                foreignPriceInKrw = BigDecimal("127916893.66"),
                fxRate = BigDecimal("1432.6"),
                observedAt = Instant.parse("2024-01-01T00:00:00Z"),
                pair = pair,
                fxSource = Exchange.FX_PROVIDER,
                fxObservedAt = fxObservedAt,
            )

            val result = repository.findLatestSnapshotByPair(pair)!!

            assertThat(result.pair).isEqualTo(pair)
            assertThat(result.fxSource).isEqualTo(Exchange.FX_PROVIDER)
            assertThat(result.fxObservedAt).isEqualTo(fxObservedAt)
            verify(exactly = 0) { premiumJpaRepository.findLatestSnapshotByPair(any(), any(), any()) }
        }
    }

    @Nested
    inner class FindAggregation {

        @Test
        fun `캐시와 DB의 동일 bucket은 DB를 정본으로 병합한다`() {
            // given
            val symbol = Symbol("BTC")
            val pair = MarketPair.default(symbol)
            val from = Instant.parse("2024-01-01T00:00:00Z")
            val to = Instant.parse("2024-01-02T00:00:00Z")
            val snapshot = PremiumAggregationSnapshot(
                symbol = "BTC",
                high = BigDecimal("2.50"), low = BigDecimal("1.00"),
                open = BigDecimal("1.50"), close = BigDecimal("2.00"),
                avg = BigDecimal("1.75"), count = 60,
                observedAt = from,
            )

            every { premiumAggregationCacheReader.findByInterval(pair, "1h", from, to) } returns listOf(snapshot)
            every { premiumAggregationQueryRepository.findByInterval(pair, "1h", from, to) } returns listOf(
                snapshot.copy(high = BigDecimal("2.60")),
            )

            // when
            val result = repository.findAggregationByPair(MarketPair.default(symbol), "1h", from, to)

            // then
            assertThat(result).hasSize(1)
            assertThat(result[0].high).isEqualByComparingTo(BigDecimal("2.60"))
            verify(exactly = 1) { premiumAggregationQueryRepository.findByInterval(pair, "1h", from, to) }
        }

        @Test
        fun `부분 캐시는 DB의 전체 기간과 병합해 과거 bucket을 누락하지 않는다`() {
            val symbol = Symbol("BTC")
            val pair = MarketPair.default(symbol)
            val from = Instant.parse("2024-01-01T00:00:00Z")
            val middle = Instant.parse("2024-01-01T01:00:00Z")
            val to = Instant.parse("2024-01-01T02:00:00Z")
            fun snapshot(at: Instant, close: String) = PremiumAggregationSnapshot(
                symbol = "BTC",
                high = BigDecimal(close), low = BigDecimal(close),
                open = BigDecimal(close), close = BigDecimal(close),
                avg = BigDecimal(close), count = 60,
                observedAt = at,
            )
            every { premiumAggregationCacheReader.findByInterval(pair, "1h", from, to) } returns listOf(
                snapshot(middle, "2.00"),
            )
            every { premiumAggregationQueryRepository.findByInterval(pair, "1h", from, to) } returns listOf(
                snapshot(from, "1.00"),
                snapshot(middle, "2.00"),
            )

            val result = repository.findAggregationByPair(MarketPair.default(symbol), "1h", from, to)

            assertThat(result.map(PremiumAggregationSnapshot::observedAt)).containsExactly(from, middle)
        }

        @Test
        fun `캐시 miss 시 DB fallback`() {
            // given
            val symbol = Symbol("BTC")
            val pair = MarketPair.default(symbol)
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
            )

            every { premiumAggregationCacheReader.findByInterval(pair, "1h", from, to) } returns null
            every { premiumAggregationQueryRepository.findByInterval(pair, "1h", from, to) } returns snapshots

            // when
            val result = repository.findAggregationByPair(MarketPair.default(symbol), "1h", from, to)

            // then
            assertThat(result).hasSize(1)
            assertThat(result[0].symbol).isEqualTo("BTC")
            verify(exactly = 1) { premiumAggregationQueryRepository.findByInterval(pair, "1h", from, to) }
        }

        @Test
        fun `캐시 miss + DB 결과 없으면 빈 목록 반환`() {
            // given
            val symbol = Symbol("BTC")
            val pair = MarketPair.default(symbol)
            val from = Instant.parse("2024-01-01T00:00:00Z")
            val to = Instant.parse("2024-01-02T00:00:00Z")

            every { premiumAggregationCacheReader.findByInterval(pair, "1m", from, to) } returns null
            every { premiumAggregationQueryRepository.findByInterval(pair, "1m", from, to) } returns emptyList()

            // when
            val result = repository.findAggregationByPair(MarketPair.default(symbol), "1m", from, to)

            // then
            assertThat(result).isEmpty()
        }
    }

    private fun snapshotRow(
        koreaExchange: Exchange = Exchange.BITHUMB,
    ): PremiumSnapshotRow = PremiumSnapshotRow(
        symbol = "BTC",
        premiumRate = BigDecimal("1.30"),
        koreaPrice = BigDecimal("129555000"),
        foreignPrice = BigDecimal("89277"),
        foreignPriceInKrw = BigDecimal("89277").multiply(BigDecimal("1432.6")),
        fxRate = BigDecimal("1432.6"),
        observedAt = Instant.parse("2024-01-01T00:00:00Z"),
        koreaExchange = koreaExchange,
        foreignExchange = Exchange.BINANCE,
        fxSource = Exchange.FX_PROVIDER,
        fxObservedAt = Instant.parse("2024-01-01T00:00:00Z"),
    )
}
