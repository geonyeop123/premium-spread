package io.premiumspread.infrastructure.premium

import io.premiumspread.domain.market.MarketPair
import io.premiumspread.domain.ticker.Exchange
import io.premiumspread.domain.ticker.Symbol
import io.premiumspread.infrastructure.common.persistence.jdbc.premium.PremiumAggregationQueryRepository
import io.premiumspread.config.TestConfig
import io.premiumspread.testcontainers.MySqlTestContainersConfig
import io.premiumspread.testcontainers.RedisTestContainersConfig
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDateTime
import java.util.TimeZone

@Tag("integration")
@SpringBootTest
@ActiveProfiles("test")
@Import(MySqlTestContainersConfig::class, RedisTestContainersConfig::class, TestConfig::class)
class PremiumAggregationQueryRepositoryTest @Autowired constructor(
    private val repository: PremiumAggregationQueryRepository,
    private val jdbcTemplate: JdbcTemplate,
) {

    private val pair = MarketPair.default(Symbol("BTC"))

    @BeforeEach
    fun setUp() {
        jdbcTemplate.execute("TRUNCATE TABLE premium_minute")
        jdbcTemplate.execute("TRUNCATE TABLE premium_hour")
        jdbcTemplate.execute("TRUNCATE TABLE premium_day")
    }

    private fun insertMinute(symbol: String, minuteAt: LocalDateTime, high: BigDecimal, low: BigDecimal, open: BigDecimal, close: BigDecimal, avg: BigDecimal, count: Int) {
        jdbcTemplate.update(
            "INSERT INTO premium_minute (korea_exchange, foreign_exchange, symbol, minute_at, high, low, open, close, avg, count) VALUES ('BITHUMB', 'BINANCE', ?, ?, ?, ?, ?, ?, ?, ?)",
            symbol, minuteAt, high, low, open, close, avg, count,
        )
    }

    private fun insertMinute(symbol: String, minuteAt: Instant) {
        jdbcTemplate.update(
            "INSERT INTO premium_minute (korea_exchange, foreign_exchange, symbol, minute_at, high, low, open, close, avg, count) VALUES ('BITHUMB', 'BINANCE', ?, ?, 2.50, 1.00, 1.50, 2.00, 1.75, 60)",
            symbol,
            Timestamp.from(minuteAt),
        )
    }

    private fun insertHour(symbol: String, hourAt: LocalDateTime, high: BigDecimal, low: BigDecimal, open: BigDecimal, close: BigDecimal, avg: BigDecimal, count: Int) {
        jdbcTemplate.update(
            "INSERT INTO premium_hour (korea_exchange, foreign_exchange, symbol, hour_at, high, low, open, close, avg, count) VALUES ('BITHUMB', 'BINANCE', ?, ?, ?, ?, ?, ?, ?, ?)",
            symbol, hourAt, high, low, open, close, avg, count,
        )
    }

    private fun insertDay(symbol: String, dayAt: String, high: BigDecimal, low: BigDecimal, open: BigDecimal, close: BigDecimal, avg: BigDecimal, count: Int) {
        jdbcTemplate.update(
            "INSERT INTO premium_day (korea_exchange, foreign_exchange, symbol, day_at, high, low, open, close, avg, count) VALUES ('BITHUMB', 'BINANCE', ?, ?, ?, ?, ?, ?, ?, ?)",
            symbol, dayAt, high, low, open, close, avg, count,
        )
    }

    @Nested
    @DisplayName("1m 인터벌 (premium_minute)")
    inner class MinuteInterval {

        @Test
        fun `Batch UTC Instant write를 JVM timezone과 무관하게 같은 Instant로 읽는다`() {
            val originalTimeZone = TimeZone.getDefault()
            val expected = Instant.parse("2024-01-01T06:00:00Z")
            try {
                TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Honolulu"))
                insertMinute("BTC", expected)

                val result = repository.findByInterval(pair, "1m", expected, expected.plusSeconds(60))

                assertThat(result.single().observedAt).isEqualTo(expected)
            } finally {
                TimeZone.setDefault(originalTimeZone)
            }
        }

        @Test
        fun `분 단위 집계 데이터를 조회한다`() {
            // given - Batch가 UTC 기준으로 저장 (Docker 컨테이너 TZ=UTC)
            val utc0600 = LocalDateTime.of(2024, 1, 1, 6, 0)
            val utc0601 = LocalDateTime.of(2024, 1, 1, 6, 1)
            val utc0602 = LocalDateTime.of(2024, 1, 1, 6, 2)
            insertMinute("BTC", utc0600, BigDecimal("2.50"), BigDecimal("1.00"), BigDecimal("1.50"), BigDecimal("2.00"), BigDecimal("1.75"), 60)
            insertMinute("BTC", utc0601, BigDecimal("3.00"), BigDecimal("1.50"), BigDecimal("2.00"), BigDecimal("2.50"), BigDecimal("2.25"), 55)
            insertMinute("BTC", utc0602, BigDecimal("2.80"), BigDecimal("1.20"), BigDecimal("1.80"), BigDecimal("2.30"), BigDecimal("2.00"), 50)

            // when - UTC Instant로 조회 (>= 06:00, < 06:02)
            val from = Instant.parse("2024-01-01T06:00:00Z")
            val to = Instant.parse("2024-01-01T06:02:00Z")

            val result = repository.findByInterval(pair, "1m", from, to)

            // then - 06:00, 06:01 두 건
            assertThat(result).hasSize(2)
            assertThat(result[0].high).isEqualByComparingTo(BigDecimal("2.50"))
            assertThat(result[0].observedAt).isEqualTo(from)
            assertThat(result[1].high).isEqualByComparingTo(BigDecimal("3.00"))
        }

        @Test
        fun `범위 밖 데이터는 조회되지 않는다`() {
            // given
            val utc0600 = LocalDateTime.of(2024, 1, 1, 6, 0)
            insertMinute("BTC", utc0600, BigDecimal("2.50"), BigDecimal("1.00"), BigDecimal("1.50"), BigDecimal("2.00"), BigDecimal("1.75"), 60)

            // when - 다른 시간대로 조회
            val from = Instant.parse("2024-01-01T10:00:00Z")
            val to = Instant.parse("2024-01-01T11:00:00Z")

            val result = repository.findByInterval(pair, "1m", from, to)

            // then
            assertThat(result).isEmpty()
        }

        @Test
        fun `같은 symbol과 시간의 다른 거래소 pair 집계는 조회하지 않는다`() {
            val observedAt = Instant.parse("2024-01-01T06:00:00Z")
            insertMinute("BTC", observedAt)
            jdbcTemplate.update(
                "INSERT INTO premium_minute (korea_exchange, foreign_exchange, symbol, minute_at, high, low, open, close, avg, count) VALUES ('UPBIT', 'BINANCE', 'BTC', ?, 9.00, 8.00, 8.00, 9.00, 8.50, 10)",
                Timestamp.from(observedAt),
            )

            val defaultResult = repository.findByInterval(pair, "1m", observedAt, observedAt.plusSeconds(60))
            val upbitPair = MarketPair(Symbol("BTC"), Exchange.UPBIT, Exchange.BINANCE)
            val upbitResult = repository.findByInterval(
                upbitPair,
                "1m",
                observedAt,
                observedAt.plusSeconds(60),
            )

            assertThat(defaultResult.single().high).isEqualByComparingTo("2.50")
            assertThat(upbitResult.single().high).isEqualByComparingTo("9.00")
            assertThat(upbitResult.single().pair).isEqualTo(upbitPair)
        }
    }

    @Nested
    @DisplayName("1h 인터벌 (premium_hour)")
    inner class HourInterval {

        @Test
        fun `시간 단위 집계 데이터를 조회한다`() {
            // given
            val utc0600 = LocalDateTime.of(2024, 1, 1, 6, 0)
            val utc0700 = LocalDateTime.of(2024, 1, 1, 7, 0)
            insertHour("BTC", utc0600, BigDecimal("2.50"), BigDecimal("1.00"), BigDecimal("1.50"), BigDecimal("2.00"), BigDecimal("1.75"), 60)
            insertHour("BTC", utc0700, BigDecimal("3.00"), BigDecimal("1.50"), BigDecimal("2.00"), BigDecimal("2.50"), BigDecimal("2.25"), 55)

            // when
            val from = Instant.parse("2024-01-01T06:00:00Z")
            val to = Instant.parse("2024-01-01T08:00:00Z")

            val result = repository.findByInterval(pair, "1h", from, to)

            // then
            assertThat(result).hasSize(2)
            assertThat(result[0].observedAt).isEqualTo(Instant.parse("2024-01-01T06:00:00Z"))
            assertThat(result[1].observedAt).isEqualTo(Instant.parse("2024-01-01T07:00:00Z"))
        }
    }

    @Nested
    @DisplayName("1d 인터벌 (premium_day)")
    inner class DayInterval {

        @Test
        fun `일 단위 집계 데이터를 조회한다`() {
            // given - premium_day는 DATE 타입
            insertDay("BTC", "2024-01-01", BigDecimal("2.50"), BigDecimal("1.00"), BigDecimal("1.50"), BigDecimal("2.00"), BigDecimal("1.75"), 1440)
            insertDay("BTC", "2024-01-02", BigDecimal("3.00"), BigDecimal("1.50"), BigDecimal("2.00"), BigDecimal("2.50"), BigDecimal("2.25"), 1380)

            // when
            val from = Instant.parse("2024-01-01T00:00:00Z")
            val to = Instant.parse("2024-01-03T00:00:00Z")

            val result = repository.findByInterval(pair, "1d", from, to)

            // then
            assertThat(result).hasSize(2)
            assertThat(result[0].count).isEqualTo(1440)
            assertThat(result[1].count).isEqualTo(1380)
            assertThat(result[0].observedAt).isEqualTo(Instant.parse("2023-12-31T15:00:00Z"))
            assertThat(result[1].observedAt).isEqualTo(Instant.parse("2024-01-01T15:00:00Z"))
        }

        @Test
        fun `Asia Seoul business date 경계로 조회한다`() {
            insertDay("BTC", "2024-01-01", BigDecimal("2.50"), BigDecimal("1.00"), BigDecimal("1.50"), BigDecimal("2.00"), BigDecimal("1.75"), 1440)
            insertDay("BTC", "2024-01-02", BigDecimal("3.00"), BigDecimal("1.50"), BigDecimal("2.00"), BigDecimal("2.50"), BigDecimal("2.25"), 1380)

            val result = repository.findByInterval(
                pair,
                "1d",
                Instant.parse("2023-12-31T15:00:00Z"),
                Instant.parse("2024-01-01T15:00:00Z"),
            )

            assertThat(result).singleElement().extracting("observedAt")
                .isEqualTo(Instant.parse("2023-12-31T15:00:00Z"))
        }
    }

    @Nested
    @DisplayName("심볼 필터링")
    inner class SymbolFilter {

        @Test
        fun `심볼 대소문자에 관계없이 조회한다`() {
            // given
            val utc0600 = LocalDateTime.of(2024, 1, 1, 6, 0)
            insertMinute("BTC", utc0600, BigDecimal("2.50"), BigDecimal("1.00"), BigDecimal("1.50"), BigDecimal("2.00"), BigDecimal("1.75"), 60)

            // when - 소문자로 조회
            val from = Instant.parse("2024-01-01T06:00:00Z")
            val to = Instant.parse("2024-01-01T07:00:00Z")

            val result = repository.findByInterval(pair, "1m", from, to)

            // then
            assertThat(result).hasSize(1)
            assertThat(result[0].symbol).isEqualTo("BTC")
        }

        @Test
        fun `다른 심볼은 조회되지 않는다`() {
            // given
            val utc0600 = LocalDateTime.of(2024, 1, 1, 6, 0)
            insertMinute("BTC", utc0600, BigDecimal("2.50"), BigDecimal("1.00"), BigDecimal("1.50"), BigDecimal("2.00"), BigDecimal("1.75"), 60)

            // when
            val from = Instant.parse("2024-01-01T06:00:00Z")
            val to = Instant.parse("2024-01-01T07:00:00Z")

            val result = repository.findByInterval(MarketPair.default(Symbol("ETH")), "1m", from, to)

            // then
            assertThat(result).isEmpty()
        }
    }

    @Nested
    @DisplayName("잘못된 인터벌")
    inner class InvalidInterval {

        @Test
        fun `지원하지 않는 인터벌이면 예외를 던진다`() {
            val from = Instant.parse("2024-01-01T00:00:00Z")
            val to = Instant.parse("2024-01-02T00:00:00Z")

            assertThatThrownBy {
                repository.findByInterval(pair, "5m", from, to)
            }.hasRootCauseInstanceOf(IllegalArgumentException::class.java)
                .rootCause()
                .hasMessageContaining("Invalid interval: 5m")
        }
    }

    @Nested
    @DisplayName("시간 정렬")
    inner class Ordering {

        @Test
        fun `결과는 시간순으로 정렬된다`() {
            // given - 역순으로 삽입
            val utc0602 = LocalDateTime.of(2024, 1, 1, 6, 2)
            val utc0600 = LocalDateTime.of(2024, 1, 1, 6, 0)
            val utc0601 = LocalDateTime.of(2024, 1, 1, 6, 1)
            insertMinute("BTC", utc0602, BigDecimal("2.80"), BigDecimal("1.20"), BigDecimal("1.80"), BigDecimal("2.30"), BigDecimal("2.00"), 50)
            insertMinute("BTC", utc0600, BigDecimal("2.50"), BigDecimal("1.00"), BigDecimal("1.50"), BigDecimal("2.00"), BigDecimal("1.75"), 60)
            insertMinute("BTC", utc0601, BigDecimal("3.00"), BigDecimal("1.50"), BigDecimal("2.00"), BigDecimal("2.50"), BigDecimal("2.25"), 55)

            // when
            val from = Instant.parse("2024-01-01T06:00:00Z")
            val to = Instant.parse("2024-01-01T06:03:00Z")

            val result = repository.findByInterval(pair, "1m", from, to)

            // then - 시간순 정렬
            assertThat(result).hasSize(3)
            assertThat(result[0].observedAt).isEqualTo(Instant.parse("2024-01-01T06:00:00Z"))
            assertThat(result[1].observedAt).isEqualTo(Instant.parse("2024-01-01T06:01:00Z"))
            assertThat(result[2].observedAt).isEqualTo(Instant.parse("2024-01-01T06:02:00Z"))
        }
    }
}
