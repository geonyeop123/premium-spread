package io.premiumspread.application.job.premium

import io.mockk.*
import io.premiumspread.application.common.JobResult
import io.premiumspread.application.notification.PremiumUpdatedEvent
import io.premiumspread.cache.PremiumCacheData
import io.premiumspread.cache.PremiumCacheService
import io.premiumspread.cache.TickerCacheService
import io.premiumspread.cache.FxCacheService
import io.premiumspread.calculator.PremiumCalculator
import io.premiumspread.client.TickerData
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import java.math.BigDecimal
import java.time.Instant

class PremiumRealtimeJobTest {

    private lateinit var tickerCacheService: TickerCacheService
    private lateinit var fxCacheService: FxCacheService
    private lateinit var premiumCacheService: PremiumCacheService
    private lateinit var premiumCalculator: PremiumCalculator
    private lateinit var eventPublisher: ApplicationEventPublisher
    private lateinit var job: PremiumRealtimeJob

    @BeforeEach
    fun setUp() {
        tickerCacheService = mockk()
        fxCacheService = mockk()
        premiumCacheService = mockk(relaxed = true)
        premiumCalculator = mockk()
        eventPublisher = mockk(relaxed = true)
        job = PremiumRealtimeJob(
            tickerCacheService = tickerCacheService,
            fxCacheService = fxCacheService,
            premiumCacheService = premiumCacheService,
            premiumCalculator = premiumCalculator,
            eventPublisher = eventPublisher,
        )
    }

    private val now = Instant.now()

    private fun bithumbTicker(price: String = "129555000") = TickerData(
        exchange = "bithumb",
        symbol = "btc",
        currency = "KRW",
        price = BigDecimal(price),
        volume = null,
        timestamp = now,
    )

    private fun binanceTicker(price: String = "89277") = TickerData(
        exchange = "binance",
        symbol = "btc",
        currency = "USDT",
        price = BigDecimal(price),
        volume = null,
        timestamp = now,
    )

    private fun premiumData() = PremiumCacheData(
        symbol = "btc",
        premiumRate = BigDecimal("1.2800"),
        koreaPrice = BigDecimal("129555000"),
        foreignPrice = BigDecimal("89277"),
        foreignPriceInKrw = BigDecimal("127918150"),
        fxRate = BigDecimal("1432.6"),
        observedAt = now,
    )

    @Nested
    @DisplayName("실행")
    inner class Run {

        @Test
        fun `빗썸 티커가 없으면 Skipped를 반환한다`() {
            // given
            every { tickerCacheService.get("bithumb", "btc") } returns null
            every { tickerCacheService.get("binance", "btc") } returns binanceTicker()
            every { fxCacheService.getUsdKrw() } returns BigDecimal("1432.6")

            // when
            val result = job.run()

            // then
            assertThat(result).isInstanceOf(JobResult.Skipped::class.java)
            assertThat((result as JobResult.Skipped).reason).isEqualTo("missing_data")
        }

        @Test
        fun `바이낸스 티커가 없으면 Skipped를 반환한다`() {
            // given
            every { tickerCacheService.get("bithumb", "btc") } returns bithumbTicker()
            every { tickerCacheService.get("binance", "btc") } returns null
            every { fxCacheService.getUsdKrw() } returns BigDecimal("1432.6")

            // when
            val result = job.run()

            // then
            assertThat(result).isInstanceOf(JobResult.Skipped::class.java)
            assertThat((result as JobResult.Skipped).reason).isEqualTo("missing_data")
        }

        @Test
        fun `환율 정보가 없으면 Skipped를 반환한다`() {
            // given
            every { tickerCacheService.get("bithumb", "btc") } returns bithumbTicker()
            every { tickerCacheService.get("binance", "btc") } returns binanceTicker()
            every { fxCacheService.getUsdKrw() } returns null

            // when
            val result = job.run()

            // then
            assertThat(result).isInstanceOf(JobResult.Skipped::class.java)
            assertThat((result as JobResult.Skipped).reason).isEqualTo("missing_data")
        }

        @Test
        fun `빗썸 가격이 0이면 Skipped를 반환한다`() {
            // given
            every { tickerCacheService.get("bithumb", "btc") } returns bithumbTicker("0")
            every { tickerCacheService.get("binance", "btc") } returns binanceTicker()
            every { fxCacheService.getUsdKrw() } returns BigDecimal("1432.6")

            // when
            val result = job.run()

            // then
            assertThat(result).isInstanceOf(JobResult.Skipped::class.java)
            assertThat((result as JobResult.Skipped).reason).isEqualTo("invalid_price")
        }

        @Test
        fun `바이낸스 가격이 0이면 Skipped를 반환한다`() {
            // given
            every { tickerCacheService.get("bithumb", "btc") } returns bithumbTicker()
            every { tickerCacheService.get("binance", "btc") } returns binanceTicker("0")
            every { fxCacheService.getUsdKrw() } returns BigDecimal("1432.6")

            // when
            val result = job.run()

            // then
            assertThat(result).isInstanceOf(JobResult.Skipped::class.java)
            assertThat((result as JobResult.Skipped).reason).isEqualTo("invalid_price")
        }

        @Test
        fun `환율이 0이면 Skipped를 반환한다`() {
            // given
            every { tickerCacheService.get("bithumb", "btc") } returns bithumbTicker()
            every { tickerCacheService.get("binance", "btc") } returns binanceTicker()
            every { fxCacheService.getUsdKrw() } returns BigDecimal.ZERO

            // when
            val result = job.run()

            // then
            assertThat(result).isInstanceOf(JobResult.Skipped::class.java)
            assertThat((result as JobResult.Skipped).reason).isEqualTo("invalid_price")
        }

        @Test
        fun `캐시 저장 중 예외 발생 시 Failure를 반환한다`() {
            // given
            val bithumb = bithumbTicker()
            val binance = binanceTicker()
            val fxRate = BigDecimal("1432.6")
            val premium = premiumData()

            every { tickerCacheService.get("bithumb", "btc") } returns bithumb
            every { tickerCacheService.get("binance", "btc") } returns binance
            every { fxCacheService.getUsdKrw() } returns fxRate
            every { premiumCalculator.calculate(bithumb, binance, fxRate) } returns premium
            every { premiumCacheService.save(premium) } throws RuntimeException("redis error")

            // when
            val result = job.run()

            // then
            assertThat(result).isInstanceOf(JobResult.Failure::class.java)
            assertThat((result as JobResult.Failure).exception.message).isEqualTo("redis error")
        }

        @Test
        fun `성공 시 프리미엄을 계산하고 히스토리를 항상 저장한다`() {
            // given
            val bithumb = bithumbTicker()
            val binance = binanceTicker()
            val fxRate = BigDecimal("1432.6")
            val premium = premiumData()

            every { tickerCacheService.get("bithumb", "btc") } returns bithumb
            every { tickerCacheService.get("binance", "btc") } returns binance
            every { fxCacheService.getUsdKrw() } returns fxRate
            every { premiumCalculator.calculate(bithumb, binance, fxRate) } returns premium

            // when
            val result = job.run()

            // then
            assertThat(result).isEqualTo(JobResult.Success)
            verify { premiumCacheService.save(premium) }
            verify { premiumCacheService.saveToSeconds(premium) }
            verify { premiumCacheService.saveHistory(premium) }
        }

        @Test
        fun `히스토리 저장 실패해도 Success를 반환한다`() {
            // given
            val bithumb = bithumbTicker()
            val binance = binanceTicker()
            val fxRate = BigDecimal("1432.6")
            val premium = premiumData()

            every { tickerCacheService.get("bithumb", "btc") } returns bithumb
            every { tickerCacheService.get("binance", "btc") } returns binance
            every { fxCacheService.getUsdKrw() } returns fxRate
            every { premiumCalculator.calculate(bithumb, binance, fxRate) } returns premium
            every { premiumCacheService.saveHistory(any()) } throws RuntimeException("history error")

            // when
            val result = job.run()

            // then
            assertThat(result).isEqualTo(JobResult.Success)
            verify { premiumCacheService.save(premium) }
            verify { premiumCacheService.saveToSeconds(premium) }
        }

        @Test
        fun `예외 발생 시 Failure를 반환한다`() {
            // given
            every { tickerCacheService.get("bithumb", "btc") } throws RuntimeException("redis error")

            // when
            val result = job.run()

            // then
            assertThat(result).isInstanceOf(JobResult.Failure::class.java)
            assertThat((result as JobResult.Failure).exception.message).isEqualTo("redis error")
        }

        @Test
        fun `성공 시 PremiumUpdatedEvent를 publish 한다`() {
            // given
            val bithumb = bithumbTicker()
            val binance = binanceTicker()
            val fxRate = BigDecimal("1432.6")
            val premium = premiumData()

            every { tickerCacheService.get("bithumb", "btc") } returns bithumb
            every { tickerCacheService.get("binance", "btc") } returns binance
            every { fxCacheService.getUsdKrw() } returns fxRate
            every { premiumCalculator.calculate(bithumb, binance, fxRate) } returns premium

            // when
            val result = job.run()

            // then
            assertThat(result).isEqualTo(JobResult.Success)
            verify(exactly = 1) {
                eventPublisher.publishEvent(
                    match<PremiumUpdatedEvent> {
                        it.symbol == "btc" && it.premiumRate == BigDecimal("1.2800")
                    },
                )
            }
        }

        @Test
        fun `실패 시 PremiumUpdatedEvent를 publish 하지 않는다`() {
            // given
            every { tickerCacheService.get("bithumb", "btc") } throws RuntimeException("cache error")

            // when
            val result = job.run()

            // then
            assertThat(result).isInstanceOf(JobResult.Failure::class.java)
            verify(exactly = 0) { eventPublisher.publishEvent(any()) }
        }
    }
}
