package io.premiumspread.domain.exchangerate

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

class ExchangeRateServiceTest {

    private lateinit var exchangeRateRepository: ExchangeRateRepository
    private lateinit var service: ExchangeRateService

    @BeforeEach
    fun setUp() {
        exchangeRateRepository = mockk()
        service = ExchangeRateService(exchangeRateRepository)
    }

    @Nested
    inner class FindLatestSnapshot {

        @Test
        fun `repository에 위임하여 스냅샷을 반환한다`() {
            val snapshot = ExchangeRateSnapshot(
                baseCurrency = "USD",
                quoteCurrency = "KRW",
                rate = BigDecimal("1432.6"),
                observedAt = Instant.parse("2024-01-01T00:00:00Z"),
            )
            every { exchangeRateRepository.findLatestSnapshot("usd", "krw") } returns snapshot

            val result = service.findLatestSnapshot("usd", "krw")

            assertThat(result).isNotNull
            assertThat(result!!.rate).isEqualByComparingTo(BigDecimal("1432.6"))
        }

        @Test
        fun `데이터가 없으면 null을 반환한다`() {
            every { exchangeRateRepository.findLatestSnapshot("usd", "krw") } returns null

            val result = service.findLatestSnapshot("usd", "krw")

            assertThat(result).isNull()
        }
    }
}
