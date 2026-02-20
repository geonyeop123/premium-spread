package io.premiumspread.scheduler

import io.premiumspread.repository.TickerAggregationRepository
import io.premiumspread.support.BatchIntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Instant
import java.time.temporal.ChronoUnit

@DisplayName("TickerAggregation E2E")
class TickerAggregationE2ETest : BatchIntegrationTestBase() {

    @Autowired
    lateinit var tickerAggregationScheduler: TickerAggregationScheduler

    @Autowired
    lateinit var aggregationRepository: TickerAggregationRepository

    /**
     * ticker:seconds:{exchange}:{symbol} ZSet에 초당 데이터 seed
     * value: price (BigDecimal string)
     */
    private fun seedSecondsData(exchange: String, symbol: String, timestamp: Instant, price: String) {
        val key = "ticker:seconds:$exchange:$symbol"
        redisTemplate.opsForZSet().add(key, price, timestamp.toEpochMilli().toDouble())
    }

    /**
     * ticker:minutes:{exchange}:{symbol} ZSet에 분 집계 데이터 seed
     * value format: "high:low:open:close:avg:count"
     */
    private fun seedMinutesData(exchange: String, symbol: String, timestamp: Instant, high: String, low: String) {
        val key = "ticker:minutes:$exchange:$symbol"
        val avg = (high.toBigDecimal() + low.toBigDecimal()) / 2.toBigDecimal()
        val value = "$high:$low:$low:$high:$avg:10"
        redisTemplate.opsForZSet().add(key, value, timestamp.toEpochMilli().toDouble())
    }

    /**
     * ticker:hours:{exchange}:{symbol} ZSet에 시간 집계 데이터 seed
     * value format: "high:low:open:close:avg:count"
     */
    private fun seedHoursData(exchange: String, symbol: String, timestamp: Instant, high: String, low: String) {
        val key = "ticker:hours:$exchange:$symbol"
        val avg = (high.toBigDecimal() + low.toBigDecimal()) / 2.toBigDecimal()
        val value = "$high:$low:$low:$high:$avg:60"
        redisTemplate.opsForZSet().add(key, value, timestamp.toEpochMilli().toDouble())
    }

    @Nested
    @DisplayName("aggregateMinute")
    inner class AggregateMinute {

        @Test
        fun `초당 데이터를 집계하여 분 캐시에 저장한다`() {
            // given - 이전 분 윈도우에 데이터 seed
            val now = Instant.now()
            val prevMinuteStart = now.minus(1, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MINUTES)
            seedSecondsData("bithumb", "btc", prevMinuteStart.plusSeconds(10), "129555000")
            seedSecondsData("bithumb", "btc", prevMinuteStart.plusSeconds(30), "130000000")
            seedSecondsData("bithumb", "btc", prevMinuteStart.plusSeconds(50), "129800000")

            // when
            tickerAggregationScheduler.aggregateMinute()

            // then - 분 캐시 검증
            val members = redisTemplate.opsForZSet().rangeWithScores("ticker:minutes:bithumb:btc", 0, -1)
            assertThat(members).isNotEmpty
        }

        @Test
        fun `초당 데이터를 집계하여 DB ticker_minute에 저장한다`() {
            // given
            val now = Instant.now()
            val prevMinuteStart = now.minus(1, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MINUTES)
            seedSecondsData("bithumb", "btc", prevMinuteStart.plusSeconds(10), "129555000")
            seedSecondsData("bithumb", "btc", prevMinuteStart.plusSeconds(30), "130000000")
            seedSecondsData("bithumb", "btc", prevMinuteStart.plusSeconds(50), "129800000")

            // when
            tickerAggregationScheduler.aggregateMinute()

            // then - DB 검증
            val result = aggregationRepository.findLatestMinute("bithumb", "btc")
            assertThat(result).isNotNull
            assertThat(result!!.high).isEqualByComparingTo("130000000")
            assertThat(result.low).isEqualByComparingTo("129555000")
            assertThat(result.open).isEqualByComparingTo("129555000")
            assertThat(result.close).isEqualByComparingTo("129800000")
            assertThat(result.count).isEqualTo(3)
        }
    }

    @Nested
    @DisplayName("aggregateHour")
    inner class AggregateHour {

        @Test
        fun `분 데이터를 집계하여 시간 캐시에 저장한다`() {
            // given - 이전 시간 윈도우에 데이터 seed
            val now = Instant.now()
            val prevHourStart = now.minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.HOURS)
            seedMinutesData("bithumb", "btc", prevHourStart.plus(10, ChronoUnit.MINUTES), "130000000", "129000000")
            seedMinutesData("bithumb", "btc", prevHourStart.plus(30, ChronoUnit.MINUTES), "131000000", "129500000")
            seedMinutesData("bithumb", "btc", prevHourStart.plus(50, ChronoUnit.MINUTES), "130500000", "129200000")

            // when
            tickerAggregationScheduler.aggregateHour()

            // then
            val members = redisTemplate.opsForZSet().rangeWithScores("ticker:hours:bithumb:btc", 0, -1)
            assertThat(members).isNotEmpty
        }

        @Test
        fun `분 데이터를 집계하여 DB ticker_hour에 저장한다`() {
            // given
            val now = Instant.now()
            val prevHourStart = now.minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.HOURS)
            seedMinutesData("bithumb", "btc", prevHourStart.plus(10, ChronoUnit.MINUTES), "130000000", "129000000")
            seedMinutesData("bithumb", "btc", prevHourStart.plus(30, ChronoUnit.MINUTES), "131000000", "129500000")
            seedMinutesData("bithumb", "btc", prevHourStart.plus(50, ChronoUnit.MINUTES), "130500000", "129200000")

            // when
            tickerAggregationScheduler.aggregateHour()

            // then
            val result = aggregationRepository.findLatestHour("bithumb", "btc")
            assertThat(result).isNotNull
            assertThat(result!!.high).isEqualByComparingTo("131000000")
            assertThat(result.low).isEqualByComparingTo("129000000")
            assertThat(result.count).isEqualTo(30) // 3 entries * 10 count each
        }
    }

    @Nested
    @DisplayName("aggregateDay")
    inner class AggregateDay {

        @Test
        fun `시간 데이터를 집계하여 DB ticker_day에 저장한다`() {
            // given - 이전 일 윈도우에 데이터 seed
            val now = Instant.now()
            val prevDayStart = now.minus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.DAYS)
            seedHoursData("bithumb", "btc", prevDayStart.plus(4, ChronoUnit.HOURS), "130000000", "129000000")
            seedHoursData("bithumb", "btc", prevDayStart.plus(12, ChronoUnit.HOURS), "132000000", "128000000")
            seedHoursData("bithumb", "btc", prevDayStart.plus(20, ChronoUnit.HOURS), "131000000", "128500000")

            // when
            tickerAggregationScheduler.aggregateDay()

            // then - day는 캐시 저장 없이 DB만
            val result = aggregationRepository.findLatestDay("bithumb", "btc")
            assertThat(result).isNotNull
            assertThat(result!!.high).isEqualByComparingTo("132000000")
            assertThat(result.low).isEqualByComparingTo("128000000")
            assertThat(result.count).isEqualTo(180) // 3 entries * 60 count each
        }
    }
}
