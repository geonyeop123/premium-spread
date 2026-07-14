package io.premiumspread.domain.premium

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.premiumspread.PremiumFixtures
import io.premiumspread.TickerFixtures
import io.premiumspread.domain.aggregation.AggregationZone
import io.premiumspread.domain.market.MarketPair
import io.premiumspread.domain.ticker.Exchange
import io.premiumspread.domain.ticker.Symbol
import io.premiumspread.withId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

class PremiumServiceTest {

    private lateinit var premiumRepository: PremiumRepository
    private lateinit var service: PremiumService

    @BeforeEach
    fun setUp() {
        premiumRepository = mockk()
        service = PremiumService(premiumRepository)
    }

    @Nested
    inner class Create {

        @Test
        fun `Command로 프리미엄을 생성한다`() {
            val koreaTicker = TickerFixtures.koreaTicker(symbol = "BTC", price = BigDecimal("129555000"))
            val foreignTicker = TickerFixtures.foreignTicker(symbol = "BTC", price = BigDecimal("89277"))
            val fxTicker = TickerFixtures.fxTicker(price = BigDecimal("1432.6"))

            val command = PremiumCommand.Create(
                koreaTicker = koreaTicker,
                foreignTicker = foreignTicker,
                fxTicker = fxTicker,
            )

            val premiumSlot = slot<Premium>()
            every { premiumRepository.save(capture(premiumSlot)) } answers {
                premiumSlot.captured.withId(1L)
            }

            val result = service.create(command)

            assertThat(result.id).isEqualTo(1L)
            assertThat(result.symbol.code).isEqualTo("BTC")
            assertThat(result.koreaTickerId).isEqualTo(koreaTicker.id)
            assertThat(result.foreignTickerId).isEqualTo(foreignTicker.id)
            assertThat(result.fxTickerId).isEqualTo(fxTicker.id)
            assertThat(result.premiumRate).isEqualByComparingTo(BigDecimal("1.30"))

            verify(exactly = 1) { premiumRepository.save(any()) }
        }
    }

    @Nested
    inner class Save {

        @Test
        fun `프리미엄을 저장한다`() {
            val premium = PremiumFixtures.premium()

            every { premiumRepository.save(premium) } returns premium

            val result = service.save(premium)

            assertThat(result).isEqualTo(premium)
            verify(exactly = 1) { premiumRepository.save(premium) }
        }
    }

    @Nested
    inner class FindLatestBySymbol {

        @Test
        fun `심볼로 최신 프리미엄을 조회한다`() {
            val symbol = Symbol("BTC")
            val premium = PremiumFixtures.premium(symbol = "BTC")

            every { premiumRepository.findLatestByPair(MarketPair.default(symbol)) } returns premium

            val result = service.findLatestBySymbol(symbol)

            assertThat(result).isEqualTo(premium)
        }

        @Test
        fun `프리미엄이 없으면 null을 반환한다`() {
            val symbol = Symbol("BTC")

            every { premiumRepository.findLatestByPair(MarketPair.default(symbol)) } returns null

            val result = service.findLatestBySymbol(symbol)

            assertThat(result).isNull()
        }
    }

    @Nested
    inner class FindLatestSnapshotBySymbol {

        @Test
        fun `심볼로 최신 프리미엄 스냅샷을 조회한다`() {
            val symbol = Symbol("BTC")
            val snapshot = PremiumSnapshot(
                symbol = "BTC",
                premiumRate = BigDecimal("1.30"),
                koreaPrice = BigDecimal("129555000"),
                foreignPrice = BigDecimal("89277"),
                foreignPriceInKrw = BigDecimal("127916893.2"),
                fxRate = BigDecimal("1432.6"),
                observedAt = Instant.parse("2024-01-01T00:00:00Z"),
            )

            every { premiumRepository.findLatestSnapshotByPair(MarketPair.default(symbol)) } returns snapshot

            val result = service.findLatestSnapshotBySymbol(symbol)

            assertThat(result).isEqualTo(snapshot)
        }

        @Test
        fun `스냅샷이 없으면 null을 반환한다`() {
            val symbol = Symbol("BTC")

            every { premiumRepository.findLatestSnapshotByPair(MarketPair.default(symbol)) } returns null

            val result = service.findLatestSnapshotBySymbol(symbol)

            assertThat(result).isNull()
        }
    }

    @Nested
    inner class FindLatestSnapshot {
        @Test
        fun `마켓 페어로 최신 프리미엄 스냅샷을 조회한다`() {
            val pair = MarketPair(Symbol("BTC"), Exchange.BITHUMB, Exchange.BINANCE)
            val snapshot = PremiumSnapshot(
                pair = pair,
                premiumRate = BigDecimal("1.30"),
                koreaPrice = BigDecimal("129555000"),
                foreignPrice = BigDecimal("89277"),
                foreignPriceInKrw = BigDecimal("127916893.2"),
                fxRate = BigDecimal("1432.6"),
                observedAt = Instant.parse("2024-01-01T00:00:00Z"),
            )
            every { premiumRepository.findLatestSnapshotByPair(pair) } returns snapshot

            val result = service.findLatestSnapshot(pair)

            assertThat(result).isEqualTo(snapshot)
            verify(exactly = 1) { premiumRepository.findLatestSnapshotByPair(pair) }
        }

        @Test
        fun `마켓 페어의 스냅샷이 없으면 null을 반환한다`() {
            val pair = MarketPair(Symbol("BTC"), Exchange.BITHUMB, Exchange.BINANCE)
            every { premiumRepository.findLatestSnapshotByPair(pair) } returns null

            assertThat(service.findLatestSnapshot(pair)).isNull()
        }

        @Test
        fun `같은 심볼이어도 거래소 페어별 스냅샷을 섞지 않는다`() {
            val bithumbPair = MarketPair(Symbol("BTC"), Exchange.BITHUMB, Exchange.BINANCE)
            val upbitPair = MarketPair(Symbol("BTC"), Exchange.UPBIT, Exchange.BINANCE)
            val observedAt = Instant.parse("2024-01-01T00:00:00Z")
            fun snapshot(pair: MarketPair, rate: String) = PremiumSnapshot(
                pair = pair,
                premiumRate = BigDecimal(rate),
                koreaPrice = BigDecimal("100000"),
                foreignPrice = BigDecimal("100"),
                foreignPriceInKrw = BigDecimal("100000"),
                fxRate = BigDecimal("1000"),
                observedAt = observedAt,
            )
            every { premiumRepository.findLatestSnapshotByPair(bithumbPair) } returns snapshot(bithumbPair, "1.00")
            every { premiumRepository.findLatestSnapshotByPair(upbitPair) } returns snapshot(upbitPair, "2.00")

            assertThat(service.findLatestSnapshot(bithumbPair)?.pair).isEqualTo(bithumbPair)
            assertThat(service.findLatestSnapshot(upbitPair)?.pair).isEqualTo(upbitPair)
            verify(exactly = 1) { premiumRepository.findLatestSnapshotByPair(bithumbPair) }
            verify(exactly = 1) { premiumRepository.findLatestSnapshotByPair(upbitPair) }
        }

        @Test
        fun `요청과 다른 거래소 페어의 스냅샷은 거부한다`() {
            val requested = MarketPair(Symbol("BTC"), Exchange.BITHUMB, Exchange.BINANCE)
            val returned = MarketPair(Symbol("BTC"), Exchange.UPBIT, Exchange.BINANCE)
            every { premiumRepository.findLatestSnapshotByPair(requested) } returns PremiumSnapshot(
                pair = returned,
                premiumRate = BigDecimal("1.00"),
                koreaPrice = BigDecimal("100000"),
                foreignPrice = BigDecimal("100"),
                foreignPriceInKrw = BigDecimal("100000"),
                fxRate = BigDecimal("1000"),
                observedAt = Instant.parse("2024-01-01T00:00:00Z"),
            )

            assertThatThrownBy { service.findLatestSnapshot(requested) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("pair")
        }
    }

    @Nested
    inner class FindAllBySymbolAndPeriod {

        @Test
        fun `심볼과 기간으로 프리미엄 목록을 조회한다`() {
            val symbol = Symbol("BTC")
            val from = Instant.parse("2024-01-01T00:00:00Z")
            val to = Instant.parse("2024-01-02T00:00:00Z")
            val premiums = listOf(
                PremiumFixtures.premium(symbol = "BTC", id = 1L),
                PremiumFixtures.premium(symbol = "BTC", id = 2L),
            )

            every { premiumRepository.findAllByPair(MarketPair.default(symbol), from, to) } returns premiums

            val result = service.findAllBySymbolAndPeriod(symbol, from, to)

            assertThat(result).hasSize(2)
            assertThat(result[0].id).isEqualTo(1L)
            assertThat(result[1].id).isEqualTo(2L)
        }

        @Test
        fun `프리미엄이 없으면 빈 목록을 반환한다`() {
            val symbol = Symbol("BTC")
            val from = Instant.parse("2024-01-01T00:00:00Z")
            val to = Instant.parse("2024-01-02T00:00:00Z")

            every { premiumRepository.findAllByPair(MarketPair.default(symbol), from, to) } returns emptyList()

            val result = service.findAllBySymbolAndPeriod(symbol, from, to)

            assertThat(result).isEmpty()
        }
    }

    @Nested
    inner class FindAggregation {

        @Test
        fun `심볼, 인터벌, 기간으로 집계 데이터를 조회한다`() {
            // given — from/to가 이미 정렬된 시간 (정규화 후에도 동일)
            val symbol = Symbol("BTC")
            val from = Instant.parse("2024-01-01T00:00:00Z")
            val to = Instant.parse("2024-01-01T01:00:00Z")
            val snapshots = listOf(
                PremiumAggregationSnapshot(
                    symbol = "BTC",
                    high = BigDecimal("2.50"), low = BigDecimal("1.00"),
                    open = BigDecimal("1.50"), close = BigDecimal("2.00"),
                    avg = BigDecimal("1.75"), count = 60,
                    observedAt = from,
                ),
            )

            // 정규화 후: from=00:00, to=02:00 (1h truncate + 1)
            every {
                premiumRepository.findAggregationByPair(
                    MarketPair.default(symbol), "1h", from, Instant.parse("2024-01-01T02:00:00Z"),
                )
            } returns snapshots

            // when
            val result = service.findAggregation(symbol, "1h", from, to)

            // then
            assertThat(result).hasSize(1)
            assertThat(result[0].high).isEqualByComparingTo(BigDecimal("2.50"))
        }

        @Test
        fun `집계 데이터가 없으면 빈 목록을 반환한다`() {
            val symbol = Symbol("BTC")
            val from = Instant.parse("2024-01-01T00:00:00Z")
            val to = Instant.parse("2024-01-01T00:05:00Z")

            // 정규화 후: from=00:00, to=00:06 (1m truncate + 1)
            every {
                premiumRepository.findAggregationByPair(
                    MarketPair.default(symbol), "1m", from, Instant.parse("2024-01-01T00:06:00Z"),
                )
            } returns emptyList()

            val result = service.findAggregation(symbol, "1m", from, to)

            assertThat(result).isEmpty()
        }

        @Test
        fun `from이 최대 범위를 초과하면 clamp된다`() {
            // given
            val to = Instant.parse("2026-03-03T10:00:00Z")
            val from = to.minus(Duration.ofHours(48))  // 1m 최대 24시간 초과

            every { premiumRepository.findAggregationByPair(any(), any(), any(), any()) } returns emptyList()

            // when
            service.findAggregation(Symbol("BTC"), "1m", from, to)

            // then — from이 24시간 이내로 clamp됨
            verify {
                premiumRepository.findAggregationByPair(
                    MarketPair.default(Symbol("BTC")), "1m",
                    match { it >= to.minus(Duration.ofHours(24)) },
                    any(),
                )
            }
        }

        @Test
        fun `from과 to가 interval 단위로 정규화된다`() {
            // given
            val to = Instant.parse("2026-03-03T10:30:45Z")
            val from = to.minus(Duration.ofHours(1))

            every { premiumRepository.findAggregationByPair(any(), any(), any(), any()) } returns emptyList()

            // when
            service.findAggregation(Symbol("BTC"), "1h", from, to)

            // then — from은 시간 단위 truncate, to는 다음 시간
            verify {
                premiumRepository.findAggregationByPair(
                    MarketPair.default(Symbol("BTC")), "1h",
                    Instant.parse("2026-03-03T09:00:00Z"),
                    Instant.parse("2026-03-03T11:00:00Z"),
                )
            }
        }

        @Test
        fun `지원하지 않는 interval이면 IllegalArgumentException`() {
            val now = Instant.parse("2026-03-03T10:30:45Z")
            assertThatThrownBy {
                service.findAggregation(Symbol("BTC"), "5m", now, now)
            }.isInstanceOf(IllegalArgumentException::class.java)
        }

        @Test
        fun `from과 to가 같으면 조회를 거부한다`() {
            val at = Instant.parse("2026-03-03T10:30:45Z")

            assertThatThrownBy { service.findAggregation(Symbol("BTC"), "1h", at, at) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("from < to")
        }

        @Test
        fun `from이 to보다 늦으면 조회를 거부한다`() {
            val to = Instant.parse("2026-03-03T10:30:45Z")
            val from = to.plusSeconds(1)

            assertThatThrownBy { service.findAggregation(Symbol("BTC"), "1h", from, to) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("from < to")
        }

        @Test
        fun `일 집계는 KST 업무 일자의 시작과 끝으로 정규화한다`() {
            val pair = MarketPair.default(Symbol("BTC"))
            val from = Instant.parse("2024-01-01T16:00:00Z")
            val to = Instant.parse("2024-01-02T01:00:00Z")
            every { premiumRepository.findAggregationByPair(any(), any(), any(), any()) } returns emptyList()

            service.findAggregation(pair, "1d", from, to, AggregationZone.of("Asia/Seoul"))

            verify {
                premiumRepository.findAggregationByPair(
                    pair,
                    "1d",
                    Instant.parse("2024-01-01T15:00:00Z"),
                    Instant.parse("2024-01-02T15:00:00Z"),
                )
            }
        }

        @Test
        fun `같은 구간도 UTC 일 집계 경계는 KST와 다르다`() {
            val pair = MarketPair.default(Symbol("BTC"))
            val from = Instant.parse("2024-01-01T16:00:00Z")
            val to = Instant.parse("2024-01-02T01:00:00Z")
            every { premiumRepository.findAggregationByPair(any(), any(), any(), any()) } returns emptyList()

            service.findAggregation(pair, "1d", from, to, AggregationZone.of("UTC"))

            verify {
                premiumRepository.findAggregationByPair(
                    pair,
                    "1d",
                    Instant.parse("2024-01-01T00:00:00Z"),
                    Instant.parse("2024-01-03T00:00:00Z"),
                )
            }
        }
    }
}
