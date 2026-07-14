package io.premiumspread.application.premium

import io.mockk.every
import io.mockk.mockk
import io.premiumspread.PremiumFixtures
import io.premiumspread.TickerFixtures
import io.premiumspread.application.common.ApplicationError
import io.premiumspread.application.common.ApplicationException
import io.premiumspread.config.AggregationProperties
import io.premiumspread.domain.aggregation.AggregationZone
import io.premiumspread.domain.market.MarketPair
import io.premiumspread.domain.premium.PremiumAggregationSnapshot
import io.premiumspread.domain.premium.PremiumService
import io.premiumspread.domain.premium.PremiumSnapshot
import io.premiumspread.domain.ticker.Currency
import io.premiumspread.domain.ticker.Exchange
import io.premiumspread.domain.ticker.Quote
import io.premiumspread.domain.ticker.Symbol
import io.premiumspread.domain.ticker.TickerService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

class PremiumFacadeTest {
    private lateinit var tickerService: TickerService
    private lateinit var premiumService: PremiumService
    private lateinit var facade: PremiumFacade

    @BeforeEach
    fun setUp() {
        tickerService = mockk()
        premiumService = mockk()
        facade = PremiumFacade(tickerService, premiumService, AggregationProperties("UTC"))
    }

    @Test
    fun `계산 결과는 Domain Entity가 아닌 Detail Result로 반환한다`() {
        every { tickerService.findLatest(Exchange.BITHUMB, Quote.coin(Symbol("BTC"), Currency.KRW)) } returns
            TickerFixtures.koreaTicker(exchange = Exchange.BITHUMB)
        every { tickerService.findLatest(Exchange.BINANCE, Quote.coin(Symbol("BTC"), Currency.USD)) } returns
            TickerFixtures.foreignTicker()
        every { tickerService.findLatest(Exchange.FX_PROVIDER, Quote.fx(Currency.USD, Currency.KRW)) } returns
            TickerFixtures.fxTicker()
        every { premiumService.create(any()) } returns PremiumFixtures.premium(id = 1L)

        val result = facade.calculateAndSave(PremiumCriteria.Create("BTC"))

        assertThat(result.id).isEqualTo(1L)
        assertThat(result.symbol).isEqualTo("BTC")
    }

    @Test
    fun `티커 미발견은 안정된 Application 오류로 변환한다`() {
        every { tickerService.findLatest(Exchange.BITHUMB, Quote.coin(Symbol("BTC"), Currency.KRW)) } returns null

        assertApplicationError(ApplicationError.TICKER_NOT_FOUND) {
            facade.calculateAndSave(PremiumCriteria.Create("BTC"))
        }
    }

    @Test
    fun `현재 스냅샷은 Current Result로 변환하고 미발견은 404 오류로 변환한다`() {
        val snapshot = snapshot()
        every { premiumService.findLatestSnapshotBySymbol(Symbol("BTC")) } returns snapshot
        assertThat(facade.findCurrent(PremiumCriteria.FindCurrent("BTC")).premiumRate)
            .isEqualByComparingTo(BigDecimal("1.30"))

        every { premiumService.findLatestSnapshotBySymbol(Symbol("ETH")) } returns null
        assertApplicationError(ApplicationError.PREMIUM_NOT_FOUND) {
            facade.findCurrent(PremiumCriteria.FindCurrent("ETH"))
        }
    }

    @Test
    fun `기간 조회는 Details로 감싸고 역전 범위는 422 오류로 변환한다`() {
        val from = Instant.parse("2024-01-01T00:00:00Z")
        val to = Instant.parse("2024-01-02T00:00:00Z")
        every { premiumService.findAllBySymbolAndPeriod(Symbol("BTC"), from, to) } returns
            listOf(PremiumFixtures.premium(id = 1L))

        assertThat(facade.findByPeriod(PremiumCriteria.FindHistory("BTC", from, to)).items).hasSize(1)
        assertApplicationError(ApplicationError.INVALID_PREMIUM_INPUT) {
            facade.findByPeriod(PremiumCriteria.FindHistory("BTC", to, from))
        }
    }

    @Test
    fun `집계 페이지와 hasMore를 Facade가 계산한다`() {
        val from = Instant.parse("2024-01-01T00:00:00Z")
        val to = Instant.parse("2024-01-01T01:00:00Z")
        every {
            premiumService.findAggregation(
                MarketPair.default(Symbol("BTC")), "1m", from, to, AggregationZone.of("UTC"),
            )
        } returns listOf(
            PremiumAggregationSnapshot(
                symbol = "BTC", high = BigDecimal("2"), low = BigDecimal.ONE,
                open = BigDecimal.ONE, close = BigDecimal("2"), avg = BigDecimal("1.5"),
                count = 2, observedAt = from,
            ),
        )

        val result = facade.findAggregation(PremiumCriteria.FindAggregation("BTC", "1m", from, to))

        assertThat(result.data).hasSize(1)
        assertThat(result.hasMore).isTrue()
    }

    @Test
    fun `지원하지 않는 집계 interval은 422 오류로 변환한다`() {
        val now = Instant.parse("2024-01-01T00:00:00Z")
        assertApplicationError(ApplicationError.INVALID_PREMIUM_INPUT) {
            facade.findAggregation(PremiumCriteria.FindAggregation("BTC", "5m", now, now))
        }
    }

    @Test
    fun `adapter 불변식 위반은 사용자 입력 오류로 숨기지 않는다`() {
        every { premiumService.findLatestSnapshotBySymbol(Symbol("BTC")) } throws
            IllegalArgumentException("snapshot pair mismatch")

        assertThatThrownBy { facade.findCurrent(PremiumCriteria.FindCurrent("BTC")) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .isNotInstanceOf(ApplicationException::class.java)
    }

    private fun assertApplicationError(expected: ApplicationError, block: () -> Unit) {
        assertThatThrownBy { block() }
            .isInstanceOf(ApplicationException::class.java)
            .hasFieldOrPropertyWithValue("error", expected)
    }

    private fun snapshot() = PremiumSnapshot(
        pair = MarketPair.default(Symbol("BTC")),
        premiumRate = BigDecimal("1.30"),
        koreaPrice = BigDecimal("129555000"),
        foreignPrice = BigDecimal("89277"),
        foreignPriceInKrw = BigDecimal("127916893.2"),
        fxRate = BigDecimal("1432.6"),
        observedAt = Instant.parse("2024-01-01T00:00:00Z"),
    )
}
