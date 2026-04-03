package io.premiumspread.infrastructure.warmup

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.premiumspread.domain.premium.PremiumService
import io.premiumspread.domain.premium.PremiumSnapshot
import io.premiumspread.domain.ticker.Symbol
import io.premiumspread.domain.ticker.TickerService
import io.premiumspread.domain.ticker.TickerSnapshot
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

class CacheWarmupServiceTest {

    private lateinit var premiumService: PremiumService
    private lateinit var tickerService: TickerService
    private lateinit var sut: CacheWarmupService

    private val defaultProperties = WarmupProperties(
        enabled = true,
        symbols = listOf("btc"),
        exchanges = listOf(
            WarmupProperties.ExchangePair("bithumb", "btc"),
            WarmupProperties.ExchangePair("binance", "btc"),
        ),
    )

    @BeforeEach
    fun setUp() {
        premiumService = mockk(relaxed = true)
        tickerService = mockk(relaxed = true)
        sut = CacheWarmupService(premiumService, tickerService, defaultProperties)
    }

    @Nested
    @DisplayName("warmup")
    inner class Warmup {

        @Test
        fun `활성화 상태에서 프리미엄과 티커 스냅샷을 조회한다`() {
            // given
            every { premiumService.findLatestSnapshotBySymbol(Symbol("btc")) } returns mockPremiumSnapshot()
            every { tickerService.findLatestSnapshot("bithumb", "btc") } returns mockTickerSnapshot("bithumb")
            every { tickerService.findLatestSnapshot("binance", "btc") } returns mockTickerSnapshot("binance")

            // when
            sut.warmup()

            // then
            verify { premiumService.findLatestSnapshotBySymbol(Symbol("btc")) }
            verify { tickerService.findLatestSnapshot("bithumb", "btc") }
            verify { tickerService.findLatestSnapshot("binance", "btc") }
        }

        @Test
        fun `비활성화 상태에서는 조회하지 않는다`() {
            // given
            val disabledProps = defaultProperties.copy(enabled = false)
            sut = CacheWarmupService(premiumService, tickerService, disabledProps)

            // when
            sut.warmup()

            // then
            verify(exactly = 0) { premiumService.findLatestSnapshotBySymbol(any()) }
            verify(exactly = 0) { tickerService.findLatestSnapshot(any(), any()) }
        }

        @Test
        fun `프리미엄 조회 실패 시 서버 시작을 막지 않는다`() {
            // given
            every { premiumService.findLatestSnapshotBySymbol(any()) } throws RuntimeException("DB error")
            every { tickerService.findLatestSnapshot(any(), any()) } returns mockTickerSnapshot("bithumb")

            // when - 예외가 전파되지 않아야 한다
            sut.warmup()

            // then - 티커 조회는 계속 진행
            verify { tickerService.findLatestSnapshot("bithumb", "btc") }
            verify { tickerService.findLatestSnapshot("binance", "btc") }
        }

        @Test
        fun `티커 조회 실패 시에도 서버 시작을 막지 않는다`() {
            // given
            every { premiumService.findLatestSnapshotBySymbol(any()) } returns mockPremiumSnapshot()
            every { tickerService.findLatestSnapshot("bithumb", "btc") } throws RuntimeException("Redis timeout")
            every { tickerService.findLatestSnapshot("binance", "btc") } returns mockTickerSnapshot("binance")

            // when - 예외가 전파되지 않아야 한다
            sut.warmup()

            // then - 나머지 조회는 계속 진행
            verify { premiumService.findLatestSnapshotBySymbol(Symbol("btc")) }
            verify { tickerService.findLatestSnapshot("binance", "btc") }
        }

        @Test
        fun `설정된 심볼 목록만큼 워밍업을 수행한다`() {
            // given
            val multiSymbolProps = defaultProperties.copy(
                symbols = listOf("btc", "eth"),
            )
            sut = CacheWarmupService(premiumService, tickerService, multiSymbolProps)
            every { premiumService.findLatestSnapshotBySymbol(any()) } returns null

            // when
            sut.warmup()

            // then
            verify { premiumService.findLatestSnapshotBySymbol(Symbol("btc")) }
            verify { premiumService.findLatestSnapshotBySymbol(Symbol("eth")) }
        }
    }

    private fun mockPremiumSnapshot() = PremiumSnapshot(
        symbol = "btc",
        premiumRate = BigDecimal("2.5"),
        koreaPrice = BigDecimal("129555000"),
        foreignPrice = BigDecimal("89277"),
        foreignPriceInKrw = BigDecimal("126500000"),
        fxRate = BigDecimal("1417.5"),
        observedAt = Instant.now(),
    )

    private fun mockTickerSnapshot(exchange: String) = TickerSnapshot(
        exchange = exchange,
        symbol = "btc",
        currency = "KRW",
        price = BigDecimal("129555000"),
        volume = null,
        observedAt = Instant.now(),
    )
}
