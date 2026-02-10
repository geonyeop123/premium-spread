package io.premiumspread.infrastructure.exchangerate

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.premiumspread.infrastructure.cache.CachedFxRate
import io.premiumspread.infrastructure.cache.FxCacheReader
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

class ExchangeRateRepositoryImplTest {

    private lateinit var fxCacheReader: FxCacheReader
    private lateinit var exchangeRateQueryRepository: ExchangeRateQueryRepository
    private lateinit var repository: ExchangeRateRepositoryImpl

    @BeforeEach
    fun setUp() {
        fxCacheReader = mockk()
        exchangeRateQueryRepository = mockk()
        repository = ExchangeRateRepositoryImpl(
            fxCacheReader = fxCacheReader,
            exchangeRateQueryRepository = exchangeRateQueryRepository,
        )
    }

    @Nested
    inner class FindLatestSnapshot {

        @Test
        fun `캐시 hit이면 캐시값으로 스냅샷을 반환한다`() {
            val cached = CachedFxRate(
                baseCurrency = "USD",
                quoteCurrency = "KRW",
                rate = BigDecimal("1432.6"),
                timestamp = Instant.parse("2024-01-01T00:00:00Z"),
            )
            every { fxCacheReader.get("usd", "krw") } returns cached

            val result = repository.findLatestSnapshot("usd", "krw")

            assertThat(result).isNotNull
            assertThat(result!!.baseCurrency).isEqualTo("USD")
            assertThat(result.quoteCurrency).isEqualTo("KRW")
            assertThat(result.rate).isEqualByComparingTo(BigDecimal("1432.6"))
            verify(exactly = 0) { exchangeRateQueryRepository.findLatest(any(), any()) }
        }

        @Test
        fun `캐시 miss + DB hit이면 DB값으로 스냅샷을 반환한다`() {
            every { fxCacheReader.get("usd", "krw") } returns null

            val dbSnapshot = ExchangeRateSnapshot(
                baseCurrency = "USD",
                quoteCurrency = "KRW",
                rate = BigDecimal("1430.0"),
                observedAt = Instant.parse("2024-01-01T00:00:00Z"),
            )
            every { exchangeRateQueryRepository.findLatest("usd", "krw") } returns dbSnapshot

            val result = repository.findLatestSnapshot("usd", "krw")

            assertThat(result).isNotNull
            assertThat(result!!.baseCurrency).isEqualTo("USD")
            assertThat(result.quoteCurrency).isEqualTo("KRW")
            assertThat(result.rate).isEqualByComparingTo(BigDecimal("1430.0"))
        }

        @Test
        fun `캐시 miss + DB miss이면 null을 반환한다`() {
            every { fxCacheReader.get("usd", "krw") } returns null
            every { exchangeRateQueryRepository.findLatest("usd", "krw") } returns null

            val result = repository.findLatestSnapshot("usd", "krw")

            assertThat(result).isNull()
        }
    }
}
