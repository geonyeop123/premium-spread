package io.premiumspread.infrastructure.position

import io.premiumspread.domain.market.MarketPair
import io.premiumspread.domain.member.Member
import io.premiumspread.domain.member.MemberRepository
import io.premiumspread.domain.position.Position
import io.premiumspread.domain.position.PositionOpenSpec
import io.premiumspread.domain.position.PositionRepository
import io.premiumspread.domain.position.PositionStatus
import io.premiumspread.domain.ticker.Exchange
import io.premiumspread.domain.ticker.Symbol
import io.premiumspread.testcontainers.MySqlTestContainersConfig
import io.premiumspread.testcontainers.RedisTestContainersConfig
import io.premiumspread.utils.DatabaseCleanUp
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.ActiveProfiles
import java.math.BigDecimal
import java.time.Instant

@Tag("integration")
@SpringBootTest
@ActiveProfiles("test")
@Import(MySqlTestContainersConfig::class, RedisTestContainersConfig::class, io.premiumspread.config.TestConfig::class)
class PositionRepositoryTest @Autowired constructor(
    private val positionRepository: PositionRepository,
    private val memberRepository: MemberRepository,
    private val passwordEncoder: PasswordEncoder,
    private val databaseCleanUp: DatabaseCleanUp,
) {

    private var memberId: Long = 0L

    @BeforeEach
    fun setUp() {
        databaseCleanUp.truncateAllTables()
        val member = memberRepository.save(
            Member.create(
                email = "test@example.com",
                encodedPassword = passwordEncoder.encode("password123"),
            ),
        )
        memberId = member.id
    }

    private fun createPosition(
        symbol: String = "BTC",
    ): Position = Position.create(
        PositionOpenSpec(
            memberId = memberId,
            pair = MarketPair(Symbol(symbol), Exchange.UPBIT, Exchange.BINANCE),
            koreaQuantity = BigDecimal("0.5"),
            koreaEntryPrice = BigDecimal("129555000"),
            foreignQuantity = BigDecimal("0.5"),
            foreignEntryPrice = BigDecimal("89500"),
            foreignLeverage = 1,
            entryFxRate = BigDecimal("1432.6"),
            entryObservedAt = Instant.parse("2024-01-01T00:00:00Z"),
        ),
    )

    @Nested
    @DisplayName("save")
    inner class Save {
        @Test
        fun `should save position and return with id`() {
            // given
            val position = createPosition()

            // when
            val saved = positionRepository.save(position)

            // then
            assertThat(saved.id).isGreaterThan(0)
            assertThat(saved.symbol.code).isEqualTo("BTC")
            assertThat(saved.koreaExchange.name).isEqualTo("UPBIT")
            assertThat(saved.koreaQuantity).isEqualByComparingTo(BigDecimal("0.5"))
            assertThat(saved.foreignExchange.name).isEqualTo("BINANCE")
            assertThat(saved.status).isEqualTo(PositionStatus.OPEN)
        }

        @Test
        fun `should update position status`() {
            // given
            val saved = positionRepository.save(createPosition())
            saved.close()

            // when
            val updated = positionRepository.save(saved)

            // then
            assertThat(updated.status).isEqualTo(PositionStatus.CLOSED)
        }
    }

    @Nested
    @DisplayName("findById")
    inner class FindById {
        @Test
        fun `should return position when exists`() {
            // given
            val saved = positionRepository.save(createPosition())

            // when
            val found = positionRepository.findById(saved.id)

            // then
            assertThat(found).isNotNull
            assertThat(found!!.id).isEqualTo(saved.id)
            assertThat(found.symbol.code).isEqualTo("BTC")
            assertThat(found.koreaExchange.name).isEqualTo("UPBIT")
            assertThat(found.foreignExchange.name).isEqualTo("BINANCE")
        }

        @Test
        fun `should return null when not exists`() {
            // when
            val found = positionRepository.findById(999L)

            // then
            assertThat(found).isNull()
        }

        @Test
        fun `soft-deleted position은 ID로 조회되지 않는다`() {
            val saved = positionRepository.save(createPosition())
            saved.delete(java.time.Instant.parse("2026-07-14T03:00:00Z"))
            positionRepository.save(saved)

            assertThat(positionRepository.findById(saved.id)).isNull()
        }
    }

    @Nested
    @DisplayName("findAllByStatus")
    inner class FindAllByStatus {
        @Test
        fun `should return all open positions`() {
            // given
            positionRepository.save(createPosition(symbol = "BTC"))
            positionRepository.save(createPosition(symbol = "ETH"))
            val closedPosition = createPosition(symbol = "SOL")
            closedPosition.close()
            positionRepository.save(closedPosition)

            // when
            val found = positionRepository.findAllByStatus(PositionStatus.OPEN)

            // then
            assertThat(found).hasSize(2)
            assertThat(found).allMatch { it.status == PositionStatus.OPEN }
            assertThat(found.map { it.symbol.code }).containsExactlyInAnyOrder("BTC", "ETH")
        }

        @Test
        fun `should return all closed positions`() {
            // given
            positionRepository.save(createPosition(symbol = "BTC"))
            val closedPosition1 = createPosition(symbol = "ETH")
            closedPosition1.close()
            positionRepository.save(closedPosition1)
            val closedPosition2 = createPosition(symbol = "SOL")
            closedPosition2.close()
            positionRepository.save(closedPosition2)

            // when
            val found = positionRepository.findAllByStatus(PositionStatus.CLOSED)

            // then
            assertThat(found).hasSize(2)
            assertThat(found).allMatch { it.status == PositionStatus.CLOSED }
            assertThat(found.map { it.symbol.code }).containsExactlyInAnyOrder("ETH", "SOL")
        }

        @Test
        fun `should return empty list when no matching positions`() {
            // given
            positionRepository.save(createPosition(symbol = "BTC"))

            // when
            val found = positionRepository.findAllByStatus(PositionStatus.CLOSED)

            // then
            assertThat(found).isEmpty()
        }

        @Test
        fun `should return positions ordered by createdAt desc`() {
            // given
            val p1 = positionRepository.save(createPosition(symbol = "BTC"))
            Thread.sleep(10) // ensure different createdAt
            val p2 = positionRepository.save(createPosition(symbol = "ETH"))
            Thread.sleep(10)
            val p3 = positionRepository.save(createPosition(symbol = "SOL"))

            // when
            val found = positionRepository.findAllByStatus(PositionStatus.OPEN)

            // then
            assertThat(found).hasSize(3)
            assertThat(found[0].id).isEqualTo(p3.id)
            assertThat(found[1].id).isEqualTo(p2.id)
            assertThat(found[2].id).isEqualTo(p1.id)
        }

        @Test
        fun `status와 member 목록은 soft-deleted position을 제외한다`() {
            val active = positionRepository.save(createPosition(symbol = "BTC"))
            val deleted = positionRepository.save(createPosition(symbol = "ETH"))
            deleted.delete(java.time.Instant.parse("2026-07-14T03:00:00Z"))
            positionRepository.save(deleted)

            val byStatus = positionRepository.findAllByStatus(PositionStatus.OPEN)
            val byMember = positionRepository.findAllByMemberIdAndStatus(memberId, PositionStatus.OPEN)

            assertThat(byStatus.map(Position::id)).containsExactly(active.id)
            assertThat(byMember.map(Position::id)).containsExactly(active.id)
        }
    }

    @Nested
    inner class CountByMemberIdAndStatus {

        @Test
        fun `soft-deleted row를 제외하고 상태별 count를 반환한다`() {
            positionRepository.save(createPosition("BTC"))
            val deleted = positionRepository.save(createPosition("ETH"))
            deleted.delete(java.time.Instant.parse("2026-07-14T03:00:00Z"))
            positionRepository.save(deleted)

            assertThat(positionRepository.countByMemberIdAndStatus(memberId, PositionStatus.OPEN)).isEqualTo(1)
        }
    }
}
