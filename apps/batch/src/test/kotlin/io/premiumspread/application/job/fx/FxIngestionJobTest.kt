package io.premiumspread.application.job.fx

import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import io.premiumspread.application.common.JobResult
import io.premiumspread.cache.FxCacheService
import io.premiumspread.client.FxRateData
import io.premiumspread.client.exchangerate.ExchangeRateClient
import io.premiumspread.repository.ExchangeRateRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

class FxIngestionJobTest {

    private lateinit var exchangeRateClient: ExchangeRateClient
    private lateinit var fxCacheService: FxCacheService
    private lateinit var exchangeRateRepository: ExchangeRateRepository
    private lateinit var job: FxIngestionJob

    @BeforeEach
    fun setUp() {
        exchangeRateClient = mockk()
        fxCacheService = mockk(relaxed = true)
        exchangeRateRepository = mockk(relaxed = true)
        job = FxIngestionJob(
            exchangeRateClient = exchangeRateClient,
            fxCacheService = fxCacheService,
            exchangeRateRepository = exchangeRateRepository,
        )
    }

    private val now = Instant.now()

    private fun fxRate() = FxRateData(
        baseCurrency = "USD",
        quoteCurrency = "KRW",
        rate = BigDecimal("1432.6"),
        timestamp = now,
    )

    @Nested
    @DisplayName("실행")
    inner class Run {

        @Test
        fun `성공 시 캐시와 DB에 저장한다`() {
            // given
            val fxRate = fxRate()
            coEvery { exchangeRateClient.getUsdKrwRate() } returns fxRate

            // when
            val result = job.run()

            // then
            assertThat(result).isEqualTo(JobResult.Success)
            verify { fxCacheService.save(fxRate) }
            verify {
                exchangeRateRepository.save(
                    baseCurrency = "USD",
                    quoteCurrency = "KRW",
                    rate = BigDecimal("1432.6"),
                    observedAt = now,
                )
            }
        }

        @Test
        fun `클라이언트 예외 시 Failure를 반환한다`() {
            // given
            coEvery { exchangeRateClient.getUsdKrwRate() } throws RuntimeException("api error")

            // when
            val result = job.run()

            // then
            assertThat(result).isInstanceOf(JobResult.Failure::class.java)
            assertThat((result as JobResult.Failure).exception.message).isEqualTo("api error")
            verify(exactly = 0) { fxCacheService.save(any()) }
        }

        @Test
        fun `캐시 저장 예외 시 Failure를 반환한다`() {
            // given
            val fxRate = fxRate()
            coEvery { exchangeRateClient.getUsdKrwRate() } returns fxRate
            every { fxCacheService.save(fxRate) } throws RuntimeException("redis error")

            // when
            val result = job.run()

            // then
            assertThat(result).isInstanceOf(JobResult.Failure::class.java)
            assertThat((result as JobResult.Failure).exception.message).isEqualTo("redis error")
            verify(exactly = 0) { exchangeRateRepository.save(any(), any(), any(), any()) }
        }

        @Test
        fun `DB 저장 예외 시 Failure를 반환하고 캐시는 저장된 상태다`() {
            // given
            val fxRate = fxRate()
            coEvery { exchangeRateClient.getUsdKrwRate() } returns fxRate
            every { fxCacheService.save(fxRate) } just runs
            every {
                exchangeRateRepository.save(any(), any(), any(), any())
            } throws RuntimeException("db error")

            // when
            val result = job.run()

            // then
            assertThat(result).isInstanceOf(JobResult.Failure::class.java)
            verify(exactly = 1) { fxCacheService.save(fxRate) }
        }
    }
}
