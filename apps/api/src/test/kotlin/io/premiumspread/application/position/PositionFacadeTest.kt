package io.premiumspread.application.position

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.premiumspread.PositionFixtures
import io.premiumspread.application.common.ApplicationError
import io.premiumspread.application.common.ApplicationException
import io.premiumspread.domain.market.MarketPair
import io.premiumspread.domain.position.InvalidPositionException
import io.premiumspread.domain.position.PositionService
import io.premiumspread.domain.premium.PremiumService
import io.premiumspread.domain.premium.PremiumSnapshot
import io.premiumspread.domain.ticker.Exchange
import io.premiumspread.domain.ticker.Symbol
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class PositionFacadeTest {
    private lateinit var positionService: PositionService
    private lateinit var premiumService: PremiumService
    private lateinit var facade: PositionFacade
    private val now = Instant.parse("2026-07-14T03:00:00Z")

    @BeforeEach
    fun setUp() {
        positionService = mockk()
        premiumService = mockk()
        facade = PositionFacade(positionService, premiumService, Clock.fixed(now, ZoneOffset.UTC))
    }

    @Test
    fun `AUTO 생성은 문자열 거래소를 도메인 값으로 변환하고 Result에는 문자열만 노출한다`() {
        val pair = MarketPair(Symbol("BTC"), Exchange.UPBIT, Exchange.BINANCE)
        every { premiumService.findLatestSnapshot(pair) } returns snapshot(pair, now.minusSeconds(10))
        every { positionService.create(any()) } returns PositionFixtures.openPosition(id = 1L)

        val result = facade.openAutoPosition(openAuto())

        assertThat(result.koreaExchange).isEqualTo("UPBIT")
        assertThat(result.foreignExchange).isEqualTo("BINANCE")
        assertThat(result.status).isEqualTo("OPEN")
    }

    @Test
    fun `잘못된 거래소와 도메인 예외는 안정된 INVALID_POSITION으로 변환한다`() {
        assertApplicationError(ApplicationError.INVALID_POSITION) {
            facade.openAutoPosition(openAuto(koreaExchange = "UNKNOWN"))
        }

        every { positionService.create(any()) } throws InvalidPositionException("internal")
        assertApplicationError(ApplicationError.INVALID_POSITION) {
            facade.openManualPosition(openManual())
        }
    }

    @Test
    fun `스냅샷 없음과 stale은 충돌 Application 오류로 변환한다`() {
        val pair = MarketPair(Symbol("BTC"), Exchange.UPBIT, Exchange.BINANCE)
        every { premiumService.findLatestSnapshot(pair) } returns null
        assertApplicationError(ApplicationError.PREMIUM_SNAPSHOT_NOT_AVAILABLE) {
            facade.openAutoPosition(openAuto())
        }

        every { premiumService.findLatestSnapshot(pair) } returns snapshot(pair, now.minusSeconds(61))
        assertApplicationError(ApplicationError.STALE_PREMIUM_SNAPSHOT) {
            facade.openAutoPosition(openAuto())
        }
    }

    @Test
    fun `premium snapshot 불변식 위반은 INVALID_POSITION으로 숨기지 않는다`() {
        val pair = MarketPair(Symbol("BTC"), Exchange.UPBIT, Exchange.BINANCE)
        every { premiumService.findLatestSnapshot(pair) } throws IllegalArgumentException("snapshot pair mismatch")

        assertThatThrownBy { facade.openAutoPosition(openAuto()) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .isNotInstanceOf(ApplicationException::class.java)
    }

    @Test
    fun `단건 미발견과 소유권 위반은 같은 POSITION_NOT_FOUND로 숨긴다`() {
        every { positionService.findById(99L) } returns null
        assertApplicationError(ApplicationError.POSITION_NOT_FOUND) {
            facade.findById(PositionCriteria.FindById(99L, 1L))
        }

        every { positionService.findById(1L) } returns PositionFixtures.openPosition(id = 1L, memberId = 2L)
        assertApplicationError(ApplicationError.POSITION_NOT_FOUND) {
            facade.findById(PositionCriteria.FindById(1L, 1L))
        }
    }

    @Test
    fun `목록은 Details Result로 감싼다`() {
        every { positionService.findAllOpenByMemberId(1L) } returns listOf(PositionFixtures.openPosition(id = 1L))

        val result = facade.findAllOpenByMemberId(PositionCriteria.FindAllOpen(1L))

        assertThat(result.items).hasSize(1)
    }

    @Test
    fun `PnL은 Criteria로 조회하고 주입 Clock을 사용한다`() {
        val position = PositionFixtures.openPosition(id = 1L, memberId = 1L)
        every { positionService.findById(1L) } returns position
        every { premiumService.findLatestSnapshot(position.pair) } returns snapshot(position.pair, now)

        val result = facade.calculatePnl(PositionCriteria.CalculatePnl(1L, 1L))

        assertThat(result.calculatedAt).isEqualTo(now)
    }

    @Test
    fun `summary는 목록 조회 없이 count 결과만 사용한다`() {
        every { positionService.countOpenByMemberId(7L) } returns 2L
        every { positionService.countClosedByMemberId(7L) } returns 3L

        val result = facade.getSummary(PositionCriteria.Summary(7L))

        assertThat(result.totalPositions).isEqualTo(5)
        verify(exactly = 0) { positionService.findAllOpenByMemberId(any()) }
    }

    private fun assertApplicationError(expected: ApplicationError, block: () -> Unit) {
        assertThatThrownBy { block() }
            .isInstanceOf(ApplicationException::class.java)
            .hasFieldOrPropertyWithValue("error", expected)
    }

    private fun openAuto(koreaExchange: String = "UPBIT") = PositionCriteria.OpenAuto(
        memberId = 1L,
        symbol = "BTC",
        koreaExchange = koreaExchange,
        koreaQuantity = BigDecimal("0.5"),
        foreignExchange = "BINANCE",
        foreignQuantity = BigDecimal("0.5"),
        foreignLeverage = 1,
    )

    private fun openManual() = PositionCriteria.OpenManual(
        memberId = 1L,
        symbol = "BTC",
        koreaExchange = "UPBIT",
        koreaQuantity = BigDecimal("0.5"),
        koreaEntryPrice = BigDecimal("100"),
        foreignExchange = "BINANCE",
        foreignQuantity = BigDecimal("0.5"),
        foreignEntryPrice = BigDecimal("90"),
        foreignLeverage = 1,
        entryFxRate = BigDecimal("1400"),
        entryObservedAt = now,
    )

    private fun snapshot(pair: MarketPair, observedAt: Instant) = PremiumSnapshot(
        pair = pair,
        premiumRate = BigDecimal("1.00"),
        koreaPrice = BigDecimal("129555000"),
        foreignPrice = BigDecimal("89500"),
        foreignPriceInKrw = BigDecimal("128203000"),
        fxRate = BigDecimal("1432.6"),
        observedAt = observedAt,
    )
}
