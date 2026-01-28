package io.premiumspread.application.position

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.premiumspread.PositionFixtures
import io.premiumspread.application.ticker.PremiumFacade
import io.premiumspread.application.ticker.PremiumResult
import io.premiumspread.domain.position.Position
import io.premiumspread.domain.position.PositionRepository
import io.premiumspread.domain.position.PositionStatus
import io.premiumspread.domain.ticker.Exchange
import io.premiumspread.withId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

class PositionFacadeTest {

    private lateinit var positionRepository: PositionRepository
    private lateinit var premiumFacade: PremiumFacade
    private lateinit var facade: PositionFacade

    @BeforeEach
    fun setUp() {
        positionRepository = mockk()
        premiumFacade = mockk()
        facade = PositionFacade(positionRepository, premiumFacade)
    }

    @Nested
    inner class OpenPosition {

        @Test
        fun `포지션을 생성한다`() {
            val criteria = PositionOpenCriteria(
                symbol = "BTC",
                exchange = Exchange.UPBIT,
                quantity = BigDecimal("0.5"),
                entryPrice = BigDecimal("129555000"),
                entryFxRate = BigDecimal("1432.6"),
                entryPremiumRate = BigDecimal("1.28"),
                entryObservedAt = Instant.parse("2024-01-01T00:00:00Z"),
            )

            val positionSlot = slot<Position>()
            every { positionRepository.save(capture(positionSlot)) } answers {
                positionSlot.captured.withId(1L)
            }

            val result = facade.openPosition(criteria)

            assertThat(result.id).isEqualTo(1L)
            assertThat(result.symbol).isEqualTo("BTC")
            assertThat(result.exchange).isEqualTo(Exchange.UPBIT)
            assertThat(result.quantity).isEqualByComparingTo(BigDecimal("0.5"))
            assertThat(result.entryPrice).isEqualByComparingTo(BigDecimal("129555000"))
            assertThat(result.entryFxRate).isEqualByComparingTo(BigDecimal("1432.6"))
            assertThat(result.entryPremiumRate).isEqualByComparingTo(BigDecimal("1.28"))
            assertThat(result.status).isEqualTo(PositionStatus.OPEN)

            verify(exactly = 1) { positionRepository.save(any()) }
        }
    }

    @Nested
    inner class FindById {

        @Test
        fun `ID로 포지션을 조회한다`() {
            val position = PositionFixtures.openPosition(id = 1L)

            every { positionRepository.findById(1L) } returns position

            val result = facade.findById(1L)

            assertThat(result).isNotNull
            assertThat(result!!.id).isEqualTo(1L)
            assertThat(result.symbol).isEqualTo("BTC")
        }

        @Test
        fun `포지션이 없으면 null을 반환한다`() {
            every { positionRepository.findById(999L) } returns null

            val result = facade.findById(999L)

            assertThat(result).isNull()
        }
    }

    @Nested
    inner class FindAllOpen {

        @Test
        fun `열린 포지션 목록을 조회한다`() {
            val positions = listOf(
                PositionFixtures.openPosition(symbol = "BTC", id = 1L),
                PositionFixtures.openPosition(symbol = "ETH", id = 2L),
            )

            every { positionRepository.findAllOpen() } returns positions

            val result = facade.findAllOpen()

            assertThat(result).hasSize(2)
            assertThat(result[0].symbol).isEqualTo("BTC")
            assertThat(result[1].symbol).isEqualTo("ETH")
        }

        @Test
        fun `열린 포지션이 없으면 빈 목록을 반환한다`() {
            every { positionRepository.findAllOpen() } returns emptyList()

            val result = facade.findAllOpen()

            assertThat(result).isEmpty()
        }
    }

    @Nested
    inner class CalculatePnl {

        @Test
        fun `포지션의 PnL을 계산한다 - 프리미엄 하락 시 이익`() {
            val position = PositionFixtures.openPosition(
                id = 1L,
                entryPremiumRate = BigDecimal("3.00"),
            )
            val currentPremiumResult = PremiumResult(
                id = 1L,
                symbol = "BTC",
                koreaTickerId = 1L,
                foreignTickerId = 2L,
                fxTickerId = 3L,
                premiumRate = BigDecimal("1.00"),
                observedAt = Instant.now(),
            )

            every { positionRepository.findById(1L) } returns position
            every { premiumFacade.findLatest("BTC") } returns currentPremiumResult

            val result = facade.calculatePnl(1L)

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
                entryPremiumRate = BigDecimal("1.00"),
            )
            val currentPremiumResult = PremiumResult(
                id = 1L,
                symbol = "BTC",
                koreaTickerId = 1L,
                foreignTickerId = 2L,
                fxTickerId = 3L,
                premiumRate = BigDecimal("3.00"),
                observedAt = Instant.now(),
            )

            every { positionRepository.findById(1L) } returns position
            every { premiumFacade.findLatest("BTC") } returns currentPremiumResult

            val result = facade.calculatePnl(1L)

            assertThat(result.premiumDiff).isEqualByComparingTo(BigDecimal("2.00"))
            assertThat(result.isProfit).isFalse()
        }

        @Test
        fun `포지션이 없으면 예외를 던진다`() {
            every { positionRepository.findById(999L) } returns null

            assertThatThrownBy {
                facade.calculatePnl(999L)
            }.isInstanceOf(PositionNotFoundException::class.java)
                .hasMessageContaining("Position not found")
        }

        @Test
        fun `프리미엄이 없으면 예외를 던진다`() {
            val position = PositionFixtures.openPosition(id = 1L)

            every { positionRepository.findById(1L) } returns position
            every { premiumFacade.findLatest("BTC") } returns null

            assertThatThrownBy {
                facade.calculatePnl(1L)
            }.isInstanceOf(PremiumNotFoundException::class.java)
                .hasMessageContaining("Premium not found")
        }
    }

    @Nested
    inner class ClosePosition {

        @Test
        fun `포지션을 청산한다`() {
            val position = PositionFixtures.openPosition(id = 1L)

            every { positionRepository.findById(1L) } returns position

            val positionSlot = slot<Position>()
            every { positionRepository.save(capture(positionSlot)) } answers {
                positionSlot.captured
            }

            val result = facade.closePosition(1L)

            assertThat(result.id).isEqualTo(1L)
            assertThat(result.status).isEqualTo(PositionStatus.CLOSED)

            verify(exactly = 1) { positionRepository.save(any()) }
        }

        @Test
        fun `포지션이 없으면 예외를 던진다`() {
            every { positionRepository.findById(999L) } returns null

            assertThatThrownBy {
                facade.closePosition(999L)
            }.isInstanceOf(PositionNotFoundException::class.java)
                .hasMessageContaining("Position not found")
        }
    }
}
