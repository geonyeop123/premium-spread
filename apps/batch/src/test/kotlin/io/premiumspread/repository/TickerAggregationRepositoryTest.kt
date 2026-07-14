package io.premiumspread.repository

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

@DisplayName("TickerAggregationRepository")
class TickerAggregationRepositoryTest : BatchIntegrationTestBase() {

    @Autowired
    lateinit var repository: TickerAggregationRepository

    private val minuteAt = Instant.parse("2024-01-29T12:00:00Z")
    private val hourAt = Instant.parse("2024-01-29T12:00:00Z")
    private val dayAt = LocalDate.of(2024, 1, 29)

    private fun agg(
        exchange: String = "bithumb",
        symbol: String = "btc",
        currency: String = "KRW",
        high: String = "130000000",
        low: String = "129000000",
        open: String = "129000000",
        close: String = "130000000",
        avg: String = "129500000.0000",
        count: Int = 10,
    ) = TickerAggregation(
        exchange = exchange,
        symbol = symbol,
        currency = currency,
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
            repository.saveMinute("bithumb", "btc", minuteAt, agg())

            // then
            val result = repository.findLatestMinute("bithumb", "btc")
            assertThat(result).isNotNull
            assertThat(result!!.exchange).isEqualTo("BITHUMB")
            assertThat(result.symbol).isEqualTo("BTC")
            assertThat(result.high).isEqualByComparingTo("130000000")
            assertThat(result.low).isEqualByComparingTo("129000000")
            assertThat(result.count).isEqualTo(10)
            val storedAt = jdbcTemplate.queryForObject(
                "SELECT minute_at FROM ticker_minute WHERE exchange = 'BITHUMB' AND symbol = 'BTC'",
                Timestamp::class.java,
            )
            assertThat(storedAt!!.toInstant()).isEqualTo(minuteAt)
        }

        @Test
        fun `동일 거래소+심볼+분에 재저장 시 값이 갱신된다`() {
            // given
            repository.saveMinute("bithumb", "btc", minuteAt, agg(high = "130000000", count = 10))

            // when - 같은 (exchange, symbol, minute_at) → ON DUPLICATE KEY UPDATE
            repository.saveMinute("bithumb", "btc", minuteAt, agg(high = "132000000", count = 12))

            // then - 갱신된 값 반환
            val result = repository.findLatestMinute("bithumb", "btc")
            assertThat(result!!.high).isEqualByComparingTo("132000000")
            assertThat(result.count).isEqualTo(12)
            val count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ticker_minute WHERE exchange = 'BITHUMB' AND symbol = 'BTC'",
                Int::class.java,
            )
            assertThat(count).isEqualTo(1)
        }

        @Test
        fun `거래소가 다른 데이터는 별도로 저장된다`() {
            // given
            repository.saveMinute("bithumb", "btc", minuteAt, agg(exchange = "bithumb", high = "130000000"))
            repository.saveMinute("binance", "btc", minuteAt, agg(exchange = "binance", high = "89277"))

            // when
            val bithumbResult = repository.findLatestMinute("bithumb", "btc")
            val binanceResult = repository.findLatestMinute("binance", "btc")

            // then
            assertThat(bithumbResult!!.high).isEqualByComparingTo("130000000")
            assertThat(binanceResult!!.high).isEqualByComparingTo("89277")
        }

        @Test
        fun `여러 분 데이터 중 가장 최근 minute_at의 데이터를 반환한다`() {
            // given
            repository.saveMinute("bithumb", "btc", minuteAt, agg(high = "130000000"))
            repository.saveMinute("bithumb", "btc", minuteAt.plusSeconds(60), agg(high = "131000000"))

            // when
            val result = repository.findLatestMinute("bithumb", "btc")

            // then
            assertThat(result!!.high).isEqualByComparingTo("131000000")
        }

        @Test
        fun `데이터가 없으면 null을 반환한다`() {
            assertThat(repository.findLatestMinute("bithumb", "btc")).isNull()
        }

        @Test
        fun `다른 거래소의 데이터는 반환하지 않는다`() {
            // given - binance 데이터만 존재
            repository.saveMinute("binance", "btc", minuteAt, agg(exchange = "binance"))

            // when
            val result = repository.findLatestMinute("bithumb", "btc")

            // then
            assertThat(result).isNull()
        }
    }

    @Nested
    @DisplayName("saveHour / findLatestHour")
    inner class SaveHour {

        @Test
        fun `시간 데이터를 저장하고 조회할 수 있다`() {
            // when
            repository.saveHour("bithumb", "btc", hourAt, agg(count = 60))

            // then
            val result = repository.findLatestHour("bithumb", "btc")
            assertThat(result).isNotNull
            assertThat(result!!.count).isEqualTo(60)
            val storedAt = jdbcTemplate.queryForObject(
                "SELECT hour_at FROM ticker_hour WHERE exchange = 'BITHUMB' AND symbol = 'BTC'",
                Timestamp::class.java,
            )
            assertThat(storedAt!!.toInstant()).isEqualTo(hourAt)
        }

        @Test
        fun `동일 거래소+심볼+시각에 재저장 시 값이 갱신된다`() {
            // given
            repository.saveHour("bithumb", "btc", hourAt, agg(high = "130000000", count = 60))

            // when
            repository.saveHour("bithumb", "btc", hourAt, agg(high = "132000000", count = 65))

            // then
            val result = repository.findLatestHour("bithumb", "btc")
            assertThat(result!!.high).isEqualByComparingTo("132000000")
            assertThat(result.count).isEqualTo(65)
        }

        @Test
        fun `데이터가 없으면 null을 반환한다`() {
            assertThat(repository.findLatestHour("bithumb", "btc")).isNull()
        }
    }

    @Nested
    @DisplayName("saveDay / findLatestDay")
    inner class SaveDay {

        @Test
        fun `일 데이터를 저장하고 조회할 수 있다`() {
            // when
            repository.saveDay("bithumb", "btc", dayAt, agg(count = 1440))

            // then
            val result = repository.findLatestDay("bithumb", "btc")
            assertThat(result).isNotNull
            assertThat(result!!.count).isEqualTo(1440)
        }

        @Test
        fun `동일 거래소+심볼+날짜에 재저장 시 값이 갱신된다`() {
            // given
            repository.saveDay("bithumb", "btc", dayAt, agg(high = "130000000"))

            // when
            repository.saveDay("bithumb", "btc", dayAt, agg(high = "135000000"))

            // then
            val result = repository.findLatestDay("bithumb", "btc")
            assertThat(result!!.high).isEqualByComparingTo("135000000")
        }

        @Test
        fun `데이터가 없으면 null을 반환한다`() {
            assertThat(repository.findLatestDay("bithumb", "btc")).isNull()
        }
    }
}
