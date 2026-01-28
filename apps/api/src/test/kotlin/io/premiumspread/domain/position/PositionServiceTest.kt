package io.premiumspread.domain.position

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.premiumspread.PositionFixtures
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class PositionServiceTest {

    private lateinit var positionRepository: PositionRepository
    private lateinit var service: PositionService

    @BeforeEach
    fun setUp() {
        positionRepository = mockk()
        service = PositionService(positionRepository)
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
}
