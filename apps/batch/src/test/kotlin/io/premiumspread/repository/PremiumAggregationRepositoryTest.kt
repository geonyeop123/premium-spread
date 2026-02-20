package io.premiumspread.repository

import io.premiumspread.support.BatchIntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

@DisplayName("PremiumAggregationRepository")
class PremiumAggregationRepositoryTest : BatchIntegrationTestBase() {

    @Autowired
    lateinit var repository: PremiumAggregationRepository

    private val minuteAt = LocalDateTime.of(2024, 1, 29, 12, 0, 0)
    private val hourAt = LocalDateTime.of(2024, 1, 29, 12, 0, 0)
    private val dayAt = LocalDate.of(2024, 1, 29)

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
            repository.saveMinute("btc", minuteAt, agg())

            // then
            val result = repository.findLatestMinute("btc")
            assertThat(result).isNotNull
            assertThat(result!!.high).isEqualByComparingTo("2.00")
            assertThat(result.low).isEqualByComparingTo("1.50")
            assertThat(result.open).isEqualByComparingTo("1.50")
            assertThat(result.close).isEqualByComparingTo("2.00")
            assertThat(result.count).isEqualTo(10)
        }

        @Test
        fun `동일 심볼+분에 재저장 시 값이 갱신된다`() {
            // given
            repository.saveMinute("btc", minuteAt, agg(high = "2.00", count = 10))

            // when - 같은 (symbol, minute_at) → ON DUPLICATE KEY UPDATE
            repository.saveMinute("btc", minuteAt, agg(high = "3.00", count = 15))

            // then - 갱신된 값 반환
            val result = repository.findLatestMinute("btc")
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
            repository.saveMinute("btc", minuteAt, agg(high = "2.00"))
            repository.saveMinute("btc", minuteAt.plusMinutes(1), agg(high = "3.00"))

            // when
            val result = repository.findLatestMinute("btc")

            // then - 더 나중 시각의 데이터
            assertThat(result!!.high).isEqualByComparingTo("3.00")
        }

        @Test
        fun `데이터가 없으면 null을 반환한다`() {
            assertThat(repository.findLatestMinute("btc")).isNull()
        }
    }

    @Nested
    @DisplayName("saveHour / findLatestHour")
    inner class SaveHour {

        @Test
        fun `시간 데이터를 저장하고 조회할 수 있다`() {
            // when
            repository.saveHour("btc", hourAt, agg(high = "3.00", count = 60))

            // then
            val result = repository.findLatestHour("btc")
            assertThat(result).isNotNull
            assertThat(result!!.high).isEqualByComparingTo("3.00")
            assertThat(result.count).isEqualTo(60)
        }

        @Test
        fun `동일 심볼+시각에 재저장 시 값이 갱신된다`() {
            // given
            repository.saveHour("btc", hourAt, agg(high = "3.00", count = 60))

            // when
            repository.saveHour("btc", hourAt, agg(high = "3.50", count = 65))

            // then
            val result = repository.findLatestHour("btc")
            assertThat(result!!.high).isEqualByComparingTo("3.50")
        }

        @Test
        fun `데이터가 없으면 null을 반환한다`() {
            assertThat(repository.findLatestHour("btc")).isNull()
        }
    }

    @Nested
    @DisplayName("saveDay / findLatestDay")
    inner class SaveDay {

        @Test
        fun `일 데이터를 저장하고 조회할 수 있다`() {
            // when
            repository.saveDay("btc", dayAt, agg(high = "4.00", count = 1440))

            // then
            val result = repository.findLatestDay("btc")
            assertThat(result).isNotNull
            assertThat(result!!.high).isEqualByComparingTo("4.00")
            assertThat(result.count).isEqualTo(1440)
        }

        @Test
        fun `동일 심볼+날짜에 재저장 시 값이 갱신된다`() {
            // given
            repository.saveDay("btc", dayAt, agg(high = "4.00"))

            // when
            repository.saveDay("btc", dayAt, agg(high = "5.00"))

            // then
            val result = repository.findLatestDay("btc")
            assertThat(result!!.high).isEqualByComparingTo("5.00")
        }

        @Test
        fun `데이터가 없으면 null을 반환한다`() {
            assertThat(repository.findLatestDay("btc")).isNull()
        }
    }
}
