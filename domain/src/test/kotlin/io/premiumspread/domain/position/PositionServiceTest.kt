package io.premiumspread.domain.position

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.premiumspread.PositionFixtures
import io.premiumspread.domain.ticker.Exchange
import io.premiumspread.withId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

class PositionServiceTest {

    private lateinit var positionRepository: PositionRepository
    private lateinit var service: PositionService

    @BeforeEach
    fun setUp() {
        positionRepository = mockk()
        service = PositionService(positionRepository)
    }

    @Nested
    inner class Create {

        @Test
        fun `Command로 포지션을 생성한다`() {
            val command = PositionCommand.Create(
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

            val positionSlot = slot<Position>()
            every { positionRepository.save(capture(positionSlot)) } answers {
                positionSlot.captured.withId(1L)
            }

            val result = service.create(command)

            assertThat(result.id).isEqualTo(1L)
            assertThat(result.symbol.code).isEqualTo("BTC")
            assertThat(result.koreaExchange).isEqualTo(Exchange.UPBIT)
            assertThat(result.koreaQuantity).isEqualByComparingTo(BigDecimal("0.5"))
            assertThat(result.koreaEntryPrice).isEqualByComparingTo(BigDecimal("129555000"))
            assertThat(result.foreignExchange).isEqualTo(Exchange.BINANCE)
            assertThat(result.foreignQuantity).isEqualByComparingTo(BigDecimal("0.5"))
            assertThat(result.foreignEntryPrice).isEqualByComparingTo(BigDecimal("89500"))
            assertThat(result.foreignLeverage).isEqualTo(1)
            assertThat(result.status).isEqualTo(PositionStatus.OPEN)

            verify(exactly = 1) { positionRepository.save(any()) }
        }
    }

    @Nested
    inner class Save {

        @Test
        fun `포지션을 저장한다`() {
            val position = PositionFixtures.openPosition()

            every { positionRepository.save(position) } returns position

            val result = service.save(position)

            assertThat(result).isEqualTo(position)
            verify(exactly = 1) { positionRepository.save(position) }
        }
    }

    @Nested
    inner class FindById {

        @Test
        fun `ID로 포지션을 조회한다`() {
            val position = PositionFixtures.openPosition(id = 1L)

            every { positionRepository.findById(1L) } returns position

            val result = service.findById(1L)

            assertThat(result).isEqualTo(position)
        }

        @Test
        fun `포지션이 없으면 null을 반환한다`() {
            every { positionRepository.findById(999L) } returns null

            val result = service.findById(999L)

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

            val result = service.findAllOpen()

            assertThat(result).hasSize(2)
            assertThat(result[0].symbol.code).isEqualTo("BTC")
            assertThat(result[1].symbol.code).isEqualTo("ETH")
        }

        @Test
        fun `열린 포지션이 없으면 빈 목록을 반환한다`() {
            every { positionRepository.findAllOpen() } returns emptyList()

            val result = service.findAllOpen()

            assertThat(result).isEmpty()
        }
    }

    @Nested
    inner class CountByMemberId {

        @Test
        fun `회원의 상태별 포지션을 엔티티 로딩 없이 집계한다`() {
            every { positionRepository.countByMemberIdAndStatus(7L, PositionStatus.OPEN) } returns 2L
            every { positionRepository.countByMemberIdAndStatus(7L, PositionStatus.CLOSED) } returns 3L

            assertThat(service.countOpenByMemberId(7L)).isEqualTo(2L)
            assertThat(service.countClosedByMemberId(7L)).isEqualTo(3L)

            verify(exactly = 1) { positionRepository.countByMemberIdAndStatus(7L, PositionStatus.OPEN) }
            verify(exactly = 1) { positionRepository.countByMemberIdAndStatus(7L, PositionStatus.CLOSED) }
            verify(exactly = 0) { positionRepository.findAllByMemberIdAndStatus(any(), any()) }
        }
    }

    @Nested
    inner class FindAllOpenByMemberId {

        @Test
        fun `회원별 열린 포지션 목록을 조회한다`() {
            val memberId = 1L
            val positions = listOf(
                PositionFixtures.openPosition(memberId = memberId, symbol = "BTC", id = 1L),
                PositionFixtures.openPosition(memberId = memberId, symbol = "ETH", id = 2L),
            )

            every { positionRepository.findAllOpenByMemberId(memberId) } returns positions

            val result = service.findAllOpenByMemberId(memberId)

            assertThat(result).hasSize(2)
            assertThat(result[0].symbol.code).isEqualTo("BTC")
            assertThat(result[1].symbol.code).isEqualTo("ETH")
        }

        @Test
        fun `회원의 열린 포지션이 없으면 빈 목록을 반환한다`() {
            every { positionRepository.findAllOpenByMemberId(999L) } returns emptyList()

            val result = service.findAllOpenByMemberId(999L)

            assertThat(result).isEmpty()
        }
    }
}
