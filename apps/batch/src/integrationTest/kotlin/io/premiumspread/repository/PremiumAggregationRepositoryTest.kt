package io.premiumspread.repository

import io.premiumspread.domain.market.MarketPair
import io.premiumspread.domain.ticker.Exchange
import io.premiumspread.domain.ticker.Symbol
import io.premiumspread.infrastructure.common.persistence.jdbc.premium.PremiumAggregation
import io.premiumspread.infrastructure.common.persistence.jdbc.premium.PremiumAggregationRepository
import io.premiumspread.support.BatchIntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate

@DisplayName("PremiumAggregationRepository")
class PremiumAggregationRepositoryTest : BatchIntegrationTestBase() {

    @Autowired
    lateinit var repository: PremiumAggregationRepository

    private val minuteAt = Instant.parse("2024-01-29T12:00:00Z")
    private val hourAt = Instant.parse("2024-01-29T12:00:00Z")
    private val dayAt = LocalDate.of(2024, 1, 29)
    private val pair = MarketPair.default(Symbol("BTC"))

    private fun agg(
        high: String = "2.00",
        low: String = "1.50",
        open: String = "1.50",
        close: String = "2.00",
        avg: String = "1.7500",
        count: Int = 10,
    ) = PremiumAggregation(
        symbol = "btc",
        high = BigDecimal(high),
        low = BigDecimal(low),
        open = BigDecimal(open),
        close = BigDecimal(close),
        avg = BigDecimal(avg),
        count = count,
    )

    @Nested
    @DisplayName("saveMinute / findLatestMinute")
    inner class SaveMinute {

        @Test
        fun `분 데이터를 저장하고 조회할 수 있다`() {
            // when
            repository.saveMinute(pair, minuteAt, agg())

            // then
            val result = repository.findLatestMinute(pair)
            assertThat(result).isNotNull
            assertThat(result!!.high).isEqualByComparingTo("2.00")
            assertThat(result.low).isEqualByComparingTo("1.50")
            assertThat(result.open).isEqualByComparingTo("1.50")
            assertThat(result.close).isEqualByComparingTo("2.00")
            assertThat(result.count).isEqualTo(10)
            val storedAt = jdbcTemplate.queryForObject(
                "SELECT minute_at FROM premium_minute WHERE symbol = 'BTC'",
                Timestamp::class.java,
            )
            assertThat(storedAt!!.toInstant()).isEqualTo(minuteAt)
        }

        @Test
        fun `동일 심볼+분에 재저장 시 값이 갱신된다`() {
            // given
            repository.saveMinute(pair, minuteAt, agg(high = "2.00", count = 10))

            // when - 같은 (symbol, minute_at) → ON DUPLICATE KEY UPDATE
            repository.saveMinute(pair, minuteAt, agg(high = "3.00", count = 15))

            // then - 갱신된 값 반환
            val result = repository.findLatestMinute(pair)
            assertThat(result!!.high).isEqualByComparingTo("3.00")
            assertThat(result.count).isEqualTo(15)
            val count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM premium_minute WHERE symbol = 'BTC'",
                Int::class.java,
            )
            assertThat(count).isEqualTo(1)
        }

        @Test
        fun `여러 분 데이터 중 가장 최근 minute_at의 데이터를 반환한다`() {
            // given
            repository.saveMinute(pair, minuteAt, agg(high = "2.00"))
            repository.saveMinute(pair, minuteAt.plusSeconds(60), agg(high = "3.00"))

            // when
            val result = repository.findLatestMinute(pair)

            // then - 더 나중 시각의 데이터
            assertThat(result!!.high).isEqualByComparingTo("3.00")
        }

        @Test
        fun `데이터가 없으면 null을 반환한다`() {
            assertThat(repository.findLatestMinute(pair)).isNull()
        }

        @Test
        fun `같은 symbol과 bucket도 거래소 pair가 다르면 서로 덮어쓰지 않는다`() {
            val upbitPair = MarketPair(Symbol("BTC"), Exchange.UPBIT, Exchange.BINANCE)
            repository.saveMinute(pair, minuteAt, agg(high = "2.00"))
            repository.saveMinute(upbitPair, minuteAt, agg(high = "7.00"))

            assertThat(repository.findLatestMinute(pair)!!.high).isEqualByComparingTo("2.00")
            assertThat(repository.findLatestMinute(upbitPair)!!.high).isEqualByComparingTo("7.00")
        }
    }

    @Nested
    @DisplayName("saveHour / findLatestHour")
    inner class SaveHour {

        @Test
        fun `시간 데이터를 저장하고 조회할 수 있다`() {
            // when
            repository.saveHour(pair, hourAt, agg(high = "3.00", count = 60))

            // then
            val result = repository.findLatestHour(pair)
            assertThat(result).isNotNull
            assertThat(result!!.high).isEqualByComparingTo("3.00")
            assertThat(result.count).isEqualTo(60)
            val storedAt = jdbcTemplate.queryForObject(
                "SELECT hour_at FROM premium_hour WHERE symbol = 'BTC'",
                Timestamp::class.java,
            )
            assertThat(storedAt!!.toInstant()).isEqualTo(hourAt)
        }

        @Test
        fun `동일 심볼+시각에 재저장 시 값이 갱신된다`() {
            // given
            repository.saveHour(pair, hourAt, agg(high = "3.00", count = 60))

            // when
            repository.saveHour(pair, hourAt, agg(high = "3.50", count = 65))

            // then
            val result = repository.findLatestHour(pair)
            assertThat(result!!.high).isEqualByComparingTo("3.50")
        }

        @Test
        fun `데이터가 없으면 null을 반환한다`() {
            assertThat(repository.findLatestHour(pair)).isNull()
        }
    }

    @Nested
    @DisplayName("saveDay / findLatestDay")
    inner class SaveDay {

        @Test
        fun `일 데이터를 저장하고 조회할 수 있다`() {
            // when
            repository.saveDay(pair, dayAt, agg(high = "4.00", count = 1440))

            // then
            val result = repository.findLatestDay(pair)
            assertThat(result).isNotNull
            assertThat(result!!.high).isEqualByComparingTo("4.00")
            assertThat(result.count).isEqualTo(1440)
        }

        @Test
        fun `동일 심볼+날짜에 재저장 시 값이 갱신된다`() {
            // given
            repository.saveDay(pair, dayAt, agg(high = "4.00"))

            // when
            repository.saveDay(pair, dayAt, agg(high = "5.00"))

            // then
            val result = repository.findLatestDay(pair)
            assertThat(result!!.high).isEqualByComparingTo("5.00")
        }

        @Test
        fun `데이터가 없으면 null을 반환한다`() {
            assertThat(repository.findLatestDay(pair)).isNull()
        }
    }
}
