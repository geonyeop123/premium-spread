package io.premiumspread.application.job.premium

import io.mockk.*
import io.premiumspread.application.common.JobResult
import io.premiumspread.application.notification.PremiumUpdatedEvent
import io.premiumspread.cache.PremiumCacheService
import io.premiumspread.cache.TickerCacheService
import io.premiumspread.cache.FxCacheService
import io.premiumspread.domain.exchangerate.ExchangeRateSnapshot
import io.premiumspread.domain.market.MarketPair
import io.premiumspread.domain.premium.PremiumSnapshot
import io.premiumspread.domain.ticker.Exchange
import io.premiumspread.domain.ticker.Symbol
import io.premiumspread.domain.ticker.TickerSnapshot
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
    private lateinit var eventPublisher: ApplicationEventPublisher
    private lateinit var job: PremiumRealtimeJob

    @BeforeEach
    fun setUp() {
        tickerCacheService = mockk()
        fxCacheService = mockk()
        premiumCacheService = mockk(relaxed = true)
        eventPublisher = mockk(relaxed = true)
        job = PremiumRealtimeJob(
            tickerCacheService = tickerCacheService,
            fxCacheService = fxCacheService,
            premiumCacheService = premiumCacheService,
            eventPublisher = eventPublisher,
        )
    }

    private val now = Instant.now()

    private fun bithumbTicker(
        price: String = "129555000",
        observedAt: Instant = now,
    ) = TickerSnapshot(
        exchange = "bithumb",
        symbol = "btc",
        currency = "KRW",
        price = BigDecimal(price),
        volume = null,
        observedAt = observedAt,
    )

    private fun binanceTicker(
        price: String = "89277",
        observedAt: Instant = now,
    ) = TickerSnapshot(
        exchange = "binance",
        symbol = "btc",
        currency = "USDT",
        price = BigDecimal(price),
        volume = null,
        observedAt = observedAt,
    )

    private fun premiumData() = PremiumSnapshot(
        pair = MarketPair.default(Symbol("btc")),
        premiumRate = BigDecimal("1.2800"),
        koreaPrice = BigDecimal("129555000"),
        foreignPrice = BigDecimal("89277"),
        foreignPriceInKrw = BigDecimal("127918150"),
        fxRate = BigDecimal("1432.6"),
        observedAt = now,
    )

    private fun fxData(
        rate: String = "1432.6",
        observedAt: Instant = now,
        source: Exchange = Exchange.FX_PROVIDER,
    ) = ExchangeRateSnapshot(
        baseCurrency = "USD",
        quoteCurrency = "KRW",
        rate = BigDecimal(rate),
        observedAt = observedAt,
        source = source,
    )

    @Nested
    @DisplayName("실행")
    inner class Run {

        @Test
        fun `빗썸 티커가 없으면 Skipped를 반환한다`() {
            // given
            every { tickerCacheService.getSnapshot("bithumb", "btc") } returns null
            every { tickerCacheService.getSnapshot("binance", "btc") } returns binanceTicker()
            every { fxCacheService.getUsdKrwSnapshot() } returns fxData()

            // when
            val result = job.run()

            // then
            assertThat(result).isInstanceOf(JobResult.Skipped::class.java)
            assertThat((result as JobResult.Skipped).reason).isEqualTo("missing_data")
        }

        @Test
        fun `바이낸스 티커가 없으면 Skipped를 반환한다`() {
            // given
            every { tickerCacheService.getSnapshot("bithumb", "btc") } returns bithumbTicker()
            every { tickerCacheService.getSnapshot("binance", "btc") } returns null
            every { fxCacheService.getUsdKrwSnapshot() } returns fxData()

            // when
            val result = job.run()

            // then
            assertThat(result).isInstanceOf(JobResult.Skipped::class.java)
            assertThat((result as JobResult.Skipped).reason).isEqualTo("missing_data")
        }

        @Test
        fun `환율 정보가 없으면 Skipped를 반환한다`() {
            // given
            every { tickerCacheService.getSnapshot("bithumb", "btc") } returns bithumbTicker()
            every { tickerCacheService.getSnapshot("binance", "btc") } returns binanceTicker()
            every { fxCacheService.getUsdKrwSnapshot() } returns null

            // when
            val result = job.run()

            // then
            assertThat(result).isInstanceOf(JobResult.Skipped::class.java)
            assertThat((result as JobResult.Skipped).reason).isEqualTo("missing_data")
        }

        @Test
        fun `빗썸 가격이 0이면 Skipped를 반환한다`() {
            // given
            every { tickerCacheService.getSnapshot("bithumb", "btc") } returns bithumbTicker("0")
            every { tickerCacheService.getSnapshot("binance", "btc") } returns binanceTicker()
            every { fxCacheService.getUsdKrwSnapshot() } returns fxData()

            // when
            val result = job.run()

            // then
            assertThat(result).isInstanceOf(JobResult.Skipped::class.java)
            assertThat((result as JobResult.Skipped).reason).isEqualTo("invalid_price")
        }

        @Test
        fun `바이낸스 가격이 0이면 Skipped를 반환한다`() {
            // given
            every { tickerCacheService.getSnapshot("bithumb", "btc") } returns bithumbTicker()
            every { tickerCacheService.getSnapshot("binance", "btc") } returns binanceTicker("0")
            every { fxCacheService.getUsdKrwSnapshot() } returns fxData()

            // when
            val result = job.run()

            // then
            assertThat(result).isInstanceOf(JobResult.Skipped::class.java)
            assertThat((result as JobResult.Skipped).reason).isEqualTo("invalid_price")
        }

        @Test
        fun `환율이 0이면 Skipped를 반환한다`() {
            // given
            every { tickerCacheService.getSnapshot("bithumb", "btc") } returns bithumbTicker()
            every { tickerCacheService.getSnapshot("binance", "btc") } returns binanceTicker()
            every { fxCacheService.getUsdKrwSnapshot() } returns fxData("0")

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
            every { tickerCacheService.getSnapshot("bithumb", "btc") } returns bithumb
            every { tickerCacheService.getSnapshot("binance", "btc") } returns binance
            every { fxCacheService.getUsdKrwSnapshot() } returns fxData(fxRate.toPlainString())
            every { premiumCacheService.save(any()) } throws RuntimeException("redis error")

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

            every { tickerCacheService.getSnapshot("bithumb", "btc") } returns bithumb
            every { tickerCacheService.getSnapshot("binance", "btc") } returns binance
            every { fxCacheService.getUsdKrwSnapshot() } returns fxData(fxRate.toPlainString())

            // when
            val result = job.run()

            // then
            assertThat(result).isEqualTo(JobResult.Success)
            verify { premiumCacheService.save(match { it.symbol.equals(premium.symbol, ignoreCase = true) }) }
            verify { premiumCacheService.saveToSeconds(any()) }
            verify { premiumCacheService.saveHistory(any()) }
        }

        @Test
        fun `프리미엄 관측 시각은 두 티커와 환율 중 가장 최신 시각이다`() {
            val koreaAt = Instant.parse("2026-05-12T00:00:01Z")
            val foreignAt = Instant.parse("2026-05-12T00:00:02Z")
            val fxAt = Instant.parse("2026-05-12T00:00:03Z")
            val captured = slot<PremiumSnapshot>()
            every { tickerCacheService.getSnapshot("bithumb", "btc") } returns bithumbTicker(observedAt = koreaAt)
            every { tickerCacheService.getSnapshot("binance", "btc") } returns binanceTicker(observedAt = foreignAt)
            every { fxCacheService.getUsdKrwSnapshot() } returns fxData(observedAt = fxAt, source = Exchange.BINANCE)
            every { premiumCacheService.save(capture(captured)) } just Runs

            val result = job.run()

            assertThat(result).isEqualTo(JobResult.Success)
            assertThat(captured.captured.observedAt).isEqualTo(fxAt)
            assertThat(captured.captured.fxObservedAt).isEqualTo(fxAt)
            assertThat(captured.captured.fxSource).isEqualTo(Exchange.BINANCE)
            verify { premiumCacheService.saveToSeconds(match { it.observedAt == fxAt }) }
            verify { premiumCacheService.saveHistory(match { it.observedAt == fxAt }) }
        }

        @Test
        fun `히스토리 저장 실패해도 Success를 반환한다`() {
            // given
            val bithumb = bithumbTicker()
            val binance = binanceTicker()
            val fxRate = BigDecimal("1432.6")
            val premium = premiumData()

            every { tickerCacheService.getSnapshot("bithumb", "btc") } returns bithumb
            every { tickerCacheService.getSnapshot("binance", "btc") } returns binance
            every { fxCacheService.getUsdKrwSnapshot() } returns fxData(fxRate.toPlainString())
            every { premiumCacheService.saveHistory(any()) } throws RuntimeException("history error")

            // when
            val result = job.run()

            // then
            assertThat(result).isEqualTo(JobResult.Success)
            verify { premiumCacheService.save(any()) }
            verify { premiumCacheService.saveToSeconds(any()) }
        }

        @Test
        fun `예외 발생 시 Failure를 반환한다`() {
            // given
            every { tickerCacheService.getSnapshot("bithumb", "btc") } throws RuntimeException("redis error")

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

            every { tickerCacheService.getSnapshot("bithumb", "btc") } returns bithumb
            every { tickerCacheService.getSnapshot("binance", "btc") } returns binance
            every { fxCacheService.getUsdKrwSnapshot() } returns fxData(fxRate.toPlainString())

            // when
            val result = job.run()

            // then
            assertThat(result).isEqualTo(JobResult.Success)
            verify(exactly = 1) {
                eventPublisher.publishEvent(
                    match<PremiumUpdatedEvent> {
                        it.symbol.equals("btc", ignoreCase = true) && it.premiumRate == BigDecimal("1.2954")
                    },
                )
            }
        }

        @Test
        fun `실패 시 PremiumUpdatedEvent를 publish 하지 않는다`() {
            // given
            every { tickerCacheService.getSnapshot("bithumb", "btc") } throws RuntimeException("cache error")

            // when
            val result = job.run()

            // then
            assertThat(result).isInstanceOf(JobResult.Failure::class.java)
            verify(exactly = 0) { eventPublisher.publishEvent(any()) }
        }
    }
}
