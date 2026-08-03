package io.premiumspread.domain.tracking

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.premiumspread.TrackingFixtures
import io.premiumspread.domain.ticker.Exchange
import io.premiumspread.withId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

class PositionServiceTest {

    private lateinit var trackingRepository: TrackingRepository
    private lateinit var service: TrackingService

    @BeforeEach
    fun setUp() {
        trackingRepository = mockk()
        service = TrackingService(trackingRepository)
    }

    @Nested
    inner class Create {

        @Test
        fun `Command로 포지션을 생성한다`() {
            val command = TrackingCommand.Create(
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

            val positionSlot = slot<Tracking>()
            every { trackingRepository.save(capture(positionSlot)) } answers {
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
            assertThat(result.status).isEqualTo(TrackingStatus.OPEN)

            verify(exactly = 1) { trackingRepository.save(any()) }
        }
    }

    @Nested
    inner class Save {

        @Test
        fun `포지션을 저장한다`() {
            val tracking = TrackingFixtures.openPosition()

            every { trackingRepository.save(tracking) } returns tracking

            val result = service.save(tracking)

            assertThat(result).isEqualTo(tracking)
            verify(exactly = 1) { trackingRepository.save(tracking) }
        }
    }

    @Nested
    inner class FindById {

        @Test
        fun `ID로 포지션을 조회한다`() {
            val tracking = TrackingFixtures.openPosition(id = 1L)

            every { trackingRepository.findById(1L) } returns tracking

            val result = service.findById(1L)

            assertThat(result).isEqualTo(tracking)
        }

        @Test
        fun `포지션이 없으면 null을 반환한다`() {
            every { trackingRepository.findById(999L) } returns null

            val result = service.findById(999L)

            assertThat(result).isNull()
        }
    }

    @Nested
    inner class FindAllActive {

        @Test
        fun `열린 포지션 목록을 조회한다`() {
            val positions = listOf(
                TrackingFixtures.openPosition(symbol = "BTC", id = 1L),
                TrackingFixtures.openPosition(symbol = "ETH", id = 2L),
            )

            every { trackingRepository.findAllOpen() } returns positions

            val result = service.findAllOpen()

            assertThat(result).hasSize(2)
            assertThat(result[0].symbol.code).isEqualTo("BTC")
            assertThat(result[1].symbol.code).isEqualTo("ETH")
        }

        @Test
        fun `열린 포지션이 없으면 빈 목록을 반환한다`() {
            every { trackingRepository.findAllOpen() } returns emptyList()

            val result = service.findAllOpen()

            assertThat(result).isEmpty()
        }
    }

    @Nested
    inner class CountByMemberId {

        @Test
        fun `회원의 상태별 포지션을 엔티티 로딩 없이 집계한다`() {
            every { trackingRepository.countActiveByMemberId(7L) } returns 2L
            every { trackingRepository.countArchivedByMemberId(7L) } returns 3L

            assertThat(service.countActiveByMemberId(7L)).isEqualTo(2L)
            assertThat(service.countArchivedByMemberId(7L)).isEqualTo(3L)

            verify(exactly = 1) { trackingRepository.countActiveByMemberId(7L) }
            verify(exactly = 1) { trackingRepository.countArchivedByMemberId(7L) }
            verify(exactly = 0) { trackingRepository.findAllByMemberIdAndStatus(any(), any()) }
        }
    }

    @Nested
    inner class FindAllOpenByMemberId {

        @Test
        fun `회원별 열린 포지션 목록을 조회한다`() {
            val memberId = 1L
            val positions = listOf(
                TrackingFixtures.openPosition(memberId = memberId, symbol = "BTC", id = 1L),
                TrackingFixtures.openPosition(memberId = memberId, symbol = "ETH", id = 2L),
            )

            every { trackingRepository.findAllActiveByMemberId(memberId) } returns positions

            val result = service.findAllActiveByMemberId(memberId)

            assertThat(result).hasSize(2)
            assertThat(result[0].symbol.code).isEqualTo("BTC")
            assertThat(result[1].symbol.code).isEqualTo("ETH")
        }

        @Test
        fun `회원의 열린 포지션이 없으면 빈 목록을 반환한다`() {
            every { trackingRepository.findAllActiveByMemberId(999L) } returns emptyList()

            val result = service.findAllActiveByMemberId(999L)

            assertThat(result).isEmpty()
        }
    }
}
