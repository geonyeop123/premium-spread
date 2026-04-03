package io.premiumspread.infrastructure.premium

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.premiumspread.domain.premium.PremiumAggregationSnapshot
import io.premiumspread.domain.premium.PremiumSnapshot
import io.premiumspread.domain.ticker.Symbol
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

class PremiumRepositoryImplTest {

    private lateinit var premiumJpaRepository: PremiumJpaRepository
    private lateinit var premiumCacheReader: PremiumCacheReader
    private lateinit var premiumAggregationCacheReader: PremiumAggregationCacheReader
    private lateinit var premiumAggregationQueryRepository: PremiumAggregationQueryRepository
    private lateinit var repository: PremiumRepositoryImpl

    @BeforeEach
    fun setUp() {
        premiumJpaRepository = mockk()
        premiumCacheReader = mockk()
        premiumAggregationCacheReader = mockk()
        premiumAggregationQueryRepository = mockk()
        repository = PremiumRepositoryImpl(
            premiumJpaRepository = premiumJpaRepository,
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
        fun `캐시 miss + DB JOIN 쿼리 결과가 있으면 스냅샷을 반환한다`() {
            every { premiumCacheReader.get("BTC") } returns null

            val snapshot = PremiumSnapshot(
                symbol = "BTC",
                premiumRate = BigDecimal("1.30"),
                koreaPrice = BigDecimal("129555000"),
                foreignPrice = BigDecimal("89277"),
                foreignPriceInKrw = BigDecimal("89277").multiply(BigDecimal("1432.6")),
                fxRate = BigDecimal("1432.6"),
                observedAt = Instant.parse("2024-01-01T00:00:00Z"),
            )
            every { premiumJpaRepository.findLatestSnapshotBySymbol("BTC") } returns snapshot

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
            every { premiumJpaRepository.findLatestSnapshotBySymbol("BTC") } returns null

            val result = repository.findLatestSnapshotBySymbol(Symbol("BTC"))

            assertThat(result).isNull()
        }
    }

    @Nested
    inner class FindAggregation {

        @Test
        fun `캐시 hit 시 DB를 조회하지 않는다`() {
            // given
            val symbol = Symbol("BTC")
            val from = Instant.parse("2024-01-01T00:00:00Z")
            val to = Instant.parse("2024-01-02T00:00:00Z")
            val snapshot = PremiumAggregationSnapshot(
                symbol = "BTC",
                high = BigDecimal("2.50"), low = BigDecimal("1.00"),
                open = BigDecimal("1.50"), close = BigDecimal("2.00"),
                avg = BigDecimal("1.75"), count = 60,
                observedAt = from,
            )

            every { premiumAggregationCacheReader.findByInterval("BTC", "1h", from, to) } returns listOf(snapshot)

            // when
            val result = repository.findAggregation(symbol, "1h", from, to)

            // then
            assertThat(result).hasSize(1)
            assertThat(result[0].high).isEqualByComparingTo(BigDecimal("2.50"))
            verify(exactly = 0) { premiumAggregationQueryRepository.findByInterval(any(), any(), any(), any()) }
        }

        @Test
        fun `캐시 miss 시 DB fallback`() {
            // given
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
            )

            every { premiumAggregationCacheReader.findByInterval("BTC", "1h", from, to) } returns null
            every { premiumAggregationQueryRepository.findByInterval("BTC", "1h", from, to) } returns snapshots

            // when
            val result = repository.findAggregation(symbol, "1h", from, to)

            // then
            assertThat(result).hasSize(1)
            assertThat(result[0].symbol).isEqualTo("BTC")
            verify(exactly = 1) { premiumAggregationQueryRepository.findByInterval("BTC", "1h", from, to) }
        }

        @Test
        fun `캐시 miss + DB 결과 없으면 빈 목록 반환`() {
            // given
            val symbol = Symbol("BTC")
            val from = Instant.parse("2024-01-01T00:00:00Z")
            val to = Instant.parse("2024-01-02T00:00:00Z")

            every { premiumAggregationCacheReader.findByInterval("BTC", "1m", from, to) } returns null
            every { premiumAggregationQueryRepository.findByInterval("BTC", "1m", from, to) } returns emptyList()

            // when
            val result = repository.findAggregation(symbol, "1m", from, to)

            // then
            assertThat(result).isEmpty()
        }
    }
}
