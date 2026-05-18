package io.premiumspread.application.position

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.premiumspread.PositionFixtures
import io.premiumspread.PremiumFixtures
import io.premiumspread.domain.position.InvalidPositionException
import io.premiumspread.domain.position.Position
import io.premiumspread.domain.position.PositionCommand
import io.premiumspread.domain.position.PositionService
import io.premiumspread.domain.position.PositionStatus
import io.premiumspread.domain.premium.PremiumService
import io.premiumspread.domain.premium.PremiumSnapshot
import io.premiumspread.domain.ticker.Exchange
import io.premiumspread.domain.ticker.Symbol
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

class PositionFacadeTest {

    private lateinit var positionService: PositionService
    private lateinit var premiumService: PremiumService
    private lateinit var facade: PositionFacade

    @BeforeEach
    fun setUp() {
        positionService = mockk()
        premiumService = mockk()
        facade = PositionFacade(positionService, premiumService)
    }

    @Nested
    inner class OpenAutoPosition {

        @Test
        fun `최신 프리미엄 스냅샷으로 포지션을 생성한다`() {
            val observedAt = Instant.now()
            val snapshot = premiumSnapshot(observedAt = observedAt)
            val criteria = PositionCriteria.OpenAuto(
                memberId = 1L,
                symbol = "BTC",
                koreaExchange = Exchange.UPBIT,
                koreaQuantity = BigDecimal("0.5"),
                foreignExchange = Exchange.BINANCE,
                foreignQuantity = BigDecimal("0.5"),
                foreignLeverage = 1,
            )
            val commandSlot = slot<PositionCommand.Create>()

            every { premiumService.findLatestSnapshotBySymbol(Symbol("BTC")) } returns snapshot
            every { positionService.create(capture(commandSlot)) } returns PositionFixtures.openPosition(
                id = 1L,
                koreaEntryPrice = snapshot.koreaPrice,
                foreignEntryPrice = snapshot.foreignPrice,
                entryFxRate = snapshot.fxRate,
                entryObservedAt = snapshot.observedAt,
            )

            val result = facade.openAutoPosition(criteria)

            assertThat(result.id).isEqualTo(1L)
            assertThat(result.symbol).isEqualTo("BTC")
            assertThat(result.koreaEntryPrice).isEqualByComparingTo(snapshot.koreaPrice)
            assertThat(result.foreignEntryPrice).isEqualByComparingTo(snapshot.foreignPrice)
            assertThat(result.entryFxRate).isEqualByComparingTo(snapshot.fxRate)
            assertThat(result.entryObservedAt).isEqualTo(snapshot.observedAt)
            assertThat(result.status).isEqualTo(PositionStatus.OPEN)

            verify(exactly = 1) { premiumService.findLatestSnapshotBySymbol(Symbol("BTC")) }
            verify(exactly = 1) { positionService.create(any()) }
            assertThat(commandSlot.captured.koreaEntryPrice).isEqualByComparingTo(snapshot.koreaPrice)
            assertThat(commandSlot.captured.foreignEntryPrice).isEqualByComparingTo(snapshot.foreignPrice)
            assertThat(commandSlot.captured.entryFxRate).isEqualByComparingTo(snapshot.fxRate)
            assertThat(commandSlot.captured.entryObservedAt).isEqualTo(snapshot.observedAt)
        }

        @Test
        fun `프리미엄 스냅샷이 없으면 예외를 던진다`() {
            val criteria = openAutoCriteria(symbol = "DOGE")

            every { premiumService.findLatestSnapshotBySymbol(Symbol("DOGE")) } returns null

            assertThatThrownBy {
                facade.openAutoPosition(criteria)
            }.isInstanceOf(PremiumSnapshotNotAvailableException::class.java)
                .hasMessageContaining("Premium snapshot not available")
        }

        @Test
        fun `프리미엄 스냅샷이 오래되면 예외를 던진다`() {
            val criteria = openAutoCriteria()
            val snapshot = premiumSnapshot(observedAt = Instant.now().minusSeconds(120))

            every { premiumService.findLatestSnapshotBySymbol(Symbol("BTC")) } returns snapshot

            assertThatThrownBy {
                facade.openAutoPosition(criteria)
            }.isInstanceOf(StalePremiumSnapshotException::class.java)
                .hasMessageContaining("Premium snapshot is stale")
        }

        @Test
        fun `region 위반이면 도메인 예외를 전파한다`() {
            val criteria = openAutoCriteria(koreaExchange = Exchange.BINANCE)

            every { premiumService.findLatestSnapshotBySymbol(Symbol("BTC")) } returns premiumSnapshot()
            every { positionService.create(any()) } throws InvalidPositionException("한국 거래소가 아닙니다")

            assertThatThrownBy {
                facade.openAutoPosition(criteria)
            }.isInstanceOf(InvalidPositionException::class.java)
        }
    }

    @Nested
    inner class OpenManualPosition {

        @Test
        fun `입력값으로 포지션을 생성한다`() {
            val criteria = PositionCriteria.OpenManual(
                memberId = 1L,
                symbol = "BTC",
                koreaExchange = Exchange.UPBIT,
                koreaQuantity = BigDecimal("0.5"),
                koreaEntryPrice = BigDecimal("129555000"),
                foreignExchange = Exchange.BINANCE,
                foreignQuantity = BigDecimal("0.5"),
                foreignEntryPrice = BigDecimal("89500"),
                foreignLeverage = 1,
                entryFxRate = BigDecimal("1432.6"),
                entryObservedAt = Instant.parse("2024-01-01T00:00:00Z"),
            )
            val commandSlot = slot<PositionCommand.Create>()

            every { positionService.create(capture(commandSlot)) } returns PositionFixtures.openPosition(id = 1L)

            val result = facade.openManualPosition(criteria)

            assertThat(result.id).isEqualTo(1L)
            assertThat(result.symbol).isEqualTo("BTC")
            assertThat(result.koreaExchange).isEqualTo(Exchange.UPBIT)
            assertThat(result.foreignExchange).isEqualTo(Exchange.BINANCE)
            assertThat(result.status).isEqualTo(PositionStatus.OPEN)

            verify(exactly = 1) { positionService.create(any()) }
            assertThat(commandSlot.captured.symbol).isEqualTo("BTC")
            assertThat(commandSlot.captured.koreaEntryPrice).isEqualByComparingTo(BigDecimal("129555000"))
            assertThat(commandSlot.captured.foreignEntryPrice).isEqualByComparingTo(BigDecimal("89500"))
            assertThat(commandSlot.captured.entryFxRate).isEqualByComparingTo(BigDecimal("1432.6"))
            assertThat(commandSlot.captured.entryObservedAt).isEqualTo(Instant.parse("2024-01-01T00:00:00Z"))
        }

        @Test
        fun `region 위반이면 도메인 예외를 전파한다`() {
            val criteria = PositionCriteria.OpenManual(
                memberId = 1L,
                symbol = "BTC",
                koreaExchange = Exchange.BINANCE,
                koreaQuantity = BigDecimal("0.5"),
                koreaEntryPrice = BigDecimal("129555000"),
                foreignExchange = Exchange.BINANCE,
                foreignQuantity = BigDecimal("0.5"),
                foreignEntryPrice = BigDecimal("89500"),
                foreignLeverage = 1,
                entryFxRate = BigDecimal("1432.6"),
                entryObservedAt = Instant.parse("2024-01-01T00:00:00Z"),
            )

            every { positionService.create(any()) } throws InvalidPositionException("한국 거래소가 아닙니다")

            assertThatThrownBy {
                facade.openManualPosition(criteria)
            }.isInstanceOf(InvalidPositionException::class.java)
        }
    }

    @Nested
    inner class FindById {

        @Test
        fun `ID로 포지션을 조회한다`() {
            val position = PositionFixtures.openPosition(id = 1L, memberId = 1L)

            every { positionService.findById(1L) } returns position

            val result = facade.findById(1L, 1L)

            assertThat(result).isNotNull
            assertThat(result!!.id).isEqualTo(1L)
            assertThat(result.symbol).isEqualTo("BTC")
        }

        @Test
        fun `포지션이 없으면 null을 반환한다`() {
            every { positionService.findById(999L) } returns null

            val result = facade.findById(999L, 1L)

            assertThat(result).isNull()
        }

        @Test
        fun `다른 회원의 포지션을 조회하면 예외를 던진다`() {
            val position = PositionFixtures.openPosition(id = 1L, memberId = 1L)

            every { positionService.findById(1L) } returns position

            assertThatThrownBy {
                facade.findById(1L, 999L)
            }.isInstanceOf(PositionNotFoundException::class.java)
                .hasMessageContaining("Position not found")
        }
    }

    @Nested
    inner class FindAllOpenByMemberId {

        @Test
        fun `회원별 열린 포지션 목록을 조회한다`() {
            val positions = listOf(
                PositionFixtures.openPosition(memberId = 1L, symbol = "BTC", id = 1L),
                PositionFixtures.openPosition(memberId = 1L, symbol = "ETH", id = 2L),
            )

            every { positionService.findAllOpenByMemberId(1L) } returns positions

            val result = facade.findAllOpenByMemberId(1L)

            assertThat(result).hasSize(2)
            assertThat(result[0].symbol).isEqualTo("BTC")
            assertThat(result[1].symbol).isEqualTo("ETH")
        }

        @Test
        fun `열린 포지션이 없으면 빈 목록을 반환한다`() {
            every { positionService.findAllOpenByMemberId(1L) } returns emptyList()

            val result = facade.findAllOpenByMemberId(1L)

            assertThat(result).isEmpty()
        }
    }

    @Nested
    inner class CalculatePnl {

        @Test
        fun `포지션의 PnL을 계산한다 - 프리미엄 하락 시 이익`() {
            val position = PositionFixtures.openPosition(
                id = 1L,
                memberId = 1L,
                koreaEntryPrice = BigDecimal("103000"),
                foreignEntryPrice = BigDecimal("100"),
                entryFxRate = BigDecimal("1000"),
            )
            val currentPremium = PremiumFixtures.premiumWithRate(
                symbol = "BTC",
                premiumRate = BigDecimal("1.00"),
            )

            every { positionService.findById(1L) } returns position
            every { premiumService.findLatestBySymbol(Symbol("BTC")) } returns currentPremium

            val result = facade.calculatePnl(1L, 1L)

            assertThat(result.positionId).isEqualTo(1L)
            assertThat(result.premiumDiff).isEqualByComparingTo(BigDecimal("-2.00"))
            assertThat(result.entryPremiumRate).isEqualByComparingTo(BigDecimal("3.00"))
            assertThat(result.currentPremiumRate).isEqualByComparingTo(BigDecimal("1.00"))
            assertThat(result.isProfit).isTrue()
        }

        @Test
        fun `포지션의 PnL을 계산한다 - 프리미엄 상승 시 손실`() {
            val position = PositionFixtures.openPosition(
                id = 1L,
                memberId = 1L,
                koreaEntryPrice = BigDecimal("101000"),
                foreignEntryPrice = BigDecimal("100"),
                entryFxRate = BigDecimal("1000"),
            )
            val currentPremium = PremiumFixtures.premiumWithRate(
                symbol = "BTC",
                premiumRate = BigDecimal("3.00"),
            )

            every { positionService.findById(1L) } returns position
            every { premiumService.findLatestBySymbol(Symbol("BTC")) } returns currentPremium

            val result = facade.calculatePnl(1L, 1L)

            assertThat(result.premiumDiff).isEqualByComparingTo(BigDecimal("2.00"))
            assertThat(result.isProfit).isFalse()
        }

        @Test
        fun `포지션이 없으면 예외를 던진다`() {
            every { positionService.findById(999L) } returns null

            assertThatThrownBy {
                facade.calculatePnl(999L, 1L)
            }.isInstanceOf(PositionNotFoundException::class.java)
                .hasMessageContaining("Position not found")
        }

        @Test
        fun `프리미엄이 없으면 예외를 던진다`() {
            val position = PositionFixtures.openPosition(id = 1L, memberId = 1L)

            every { positionService.findById(1L) } returns position
            every { premiumService.findLatestBySymbol(Symbol("BTC")) } returns null

            assertThatThrownBy {
                facade.calculatePnl(1L, 1L)
            }.isInstanceOf(PremiumNotFoundException::class.java)
                .hasMessageContaining("Premium not found")
        }

        @Test
        fun `다른 회원의 포지션 PnL을 계산하면 예외를 던진다`() {
            val position = PositionFixtures.openPosition(id = 1L, memberId = 1L)

            every { positionService.findById(1L) } returns position

            assertThatThrownBy {
                facade.calculatePnl(1L, 999L)
            }.isInstanceOf(PositionNotFoundException::class.java)
                .hasMessageContaining("Position not found")
        }
    }

    @Nested
    inner class ClosePosition {

        @Test
        fun `포지션을 청산한다`() {
            val position = PositionFixtures.openPosition(id = 1L, memberId = 1L)

            every { positionService.findById(1L) } returns position

            val positionSlot = slot<Position>()
            every { positionService.save(capture(positionSlot)) } answers {
                positionSlot.captured
            }

            val result = facade.closePosition(1L, 1L)

            assertThat(result.id).isEqualTo(1L)
            assertThat(result.status).isEqualTo(PositionStatus.CLOSED)

            verify(exactly = 1) { positionService.save(any()) }
        }

        @Test
        fun `포지션이 없으면 예외를 던진다`() {
            every { positionService.findById(999L) } returns null

            assertThatThrownBy {
                facade.closePosition(999L, 1L)
            }.isInstanceOf(PositionNotFoundException::class.java)
                .hasMessageContaining("Position not found")
        }

        @Test
        fun `다른 회원의 포지션을 청산하면 예외를 던진다`() {
            val position = PositionFixtures.openPosition(id = 1L, memberId = 1L)

            every { positionService.findById(1L) } returns position

            assertThatThrownBy {
                facade.closePosition(1L, 999L)
            }.isInstanceOf(PositionNotFoundException::class.java)
                .hasMessageContaining("Position not found")
        }
    }

    private fun openAutoCriteria(
        symbol: String = "BTC",
        koreaExchange: Exchange = Exchange.UPBIT,
    ): PositionCriteria.OpenAuto = PositionCriteria.OpenAuto(
        memberId = 1L,
        symbol = symbol,
        koreaExchange = koreaExchange,
        koreaQuantity = BigDecimal("0.5"),
        foreignExchange = Exchange.BINANCE,
        foreignQuantity = BigDecimal("0.5"),
        foreignLeverage = 1,
    )

    private fun premiumSnapshot(
        observedAt: Instant = Instant.now(),
    ): PremiumSnapshot = PremiumSnapshot(
        symbol = "BTC",
        premiumRate = BigDecimal("1.04"),
        koreaPrice = BigDecimal("129555000"),
        foreignPrice = BigDecimal("89500"),
        foreignPriceInKrw = BigDecimal("128203000"),
        fxRate = BigDecimal("1432.6"),
        observedAt = observedAt,
    )
}
