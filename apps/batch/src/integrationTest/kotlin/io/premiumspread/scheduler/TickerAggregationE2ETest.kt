package io.premiumspread.interfaces.scheduling

import io.premiumspread.application.job.aggregation.TickerAggregationJob
import io.premiumspread.infrastructure.common.persistence.jdbc.ticker.TickerAggregationRepository
import io.premiumspread.application.job.aggregation.AggregationWindowPolicy
import io.premiumspread.support.BatchIntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Clock
import java.time.Instant
import java.sql.Timestamp
import java.time.temporal.ChronoUnit

@DisplayName("TickerAggregation E2E")
class TickerAggregationE2ETest : BatchIntegrationTestBase() {

    @Autowired
    lateinit var tickerAggregationJob: TickerAggregationJob

    @Autowired
    lateinit var aggregationRepository: TickerAggregationRepository

    @Autowired
    lateinit var clock: Clock

    @Autowired
    lateinit var windowPolicy: AggregationWindowPolicy

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
            val now = clock.instant()
            val prevMinuteStart = now.minus(1, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MINUTES)
            seedSecondsData("bithumb", "btc", prevMinuteStart.plusSeconds(10), "129555000")
            seedSecondsData("bithumb", "btc", prevMinuteStart.plusSeconds(30), "130000000")
            seedSecondsData("bithumb", "btc", prevMinuteStart.plusSeconds(50), "129800000")

            // when
            tickerAggregationJob.aggregateMinute()

            // then - 분 캐시 검증
            val members = redisTemplate.opsForZSet().rangeWithScores("ticker:minutes:bithumb:btc", 0, -1)
            assertThat(members).isNotEmpty
        }

        @Test
        fun `초당 데이터를 집계하여 DB ticker_minute에 저장한다`() {
            // given
            val now = clock.instant()
            val prevMinuteStart = now.minus(1, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MINUTES)
            seedSecondsData("bithumb", "btc", prevMinuteStart.plusSeconds(10), "129555000")
            seedSecondsData("bithumb", "btc", prevMinuteStart.plusSeconds(30), "130000000")
            seedSecondsData("bithumb", "btc", prevMinuteStart.plusSeconds(50), "129800000")

            // when
            tickerAggregationJob.aggregateMinute()

            // then - DB 검증
            val result = aggregationRepository.findLatestMinute("bithumb", "btc")
            assertThat(result).isNotNull
            assertThat(result!!.high).isEqualByComparingTo("130000000")
            assertThat(result.low).isEqualByComparingTo("129555000")
            assertThat(result.open).isEqualByComparingTo("129555000")
            assertThat(result.close).isEqualByComparingTo("129800000")
            assertThat(result.count).isEqualTo(3)
            val storedAt = jdbcTemplate.queryForObject(
                "SELECT minute_at FROM ticker_minute WHERE exchange = 'BITHUMB' AND symbol = 'BTC'",
                Timestamp::class.java,
            )
            assertThat(storedAt!!.toInstant()).isEqualTo(prevMinuteStart)
        }

        @Test
        fun `소스 데이터 없으면 DB에 저장하지 않는다`() {
            // given - 소스 데이터 없음 (cleanUp에서 Redis 비워짐)

            // when
            tickerAggregationJob.aggregateMinute()

            // then - 모든 거래소/심볼 저장 없음
            assertThat(aggregationRepository.findLatestMinute("bithumb", "btc")).isNull()
            assertThat(aggregationRepository.findLatestMinute("binance", "btc")).isNull()
        }

        @Test
        fun `bithumb 소스 없으면 bithumb은 저장하지 않고 binance만 저장한다`() {
            // given - binance 소스만 존재
            val now = clock.instant()
            val prevMinuteStart = now.minus(1, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MINUTES)
            seedSecondsData("binance", "btc", prevMinuteStart.plusSeconds(10), "89277")
            seedSecondsData("binance", "btc", prevMinuteStart.plusSeconds(30), "89500")
            seedSecondsData("binance", "btc", prevMinuteStart.plusSeconds(50), "89300")

            // when
            tickerAggregationJob.aggregateMinute()

            // then
            assertThat(aggregationRepository.findLatestMinute("bithumb", "btc")).isNull()
            val binanceResult = aggregationRepository.findLatestMinute("binance", "btc")
            assertThat(binanceResult).isNotNull
            assertThat(binanceResult!!.high).isEqualByComparingTo("89500")
            assertThat(binanceResult.low).isEqualByComparingTo("89277")
        }
    }

    @Nested
    @DisplayName("aggregateHour")
    inner class AggregateHour {

        @Test
        fun `분 데이터를 집계하여 시간 캐시에 저장한다`() {
            // given - 이전 시간 윈도우에 데이터 seed
            val now = clock.instant()
            val prevHourStart = now.minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.HOURS)
            seedMinutesData("bithumb", "btc", prevHourStart.plus(10, ChronoUnit.MINUTES), "130000000", "129000000")
            seedMinutesData("bithumb", "btc", prevHourStart.plus(30, ChronoUnit.MINUTES), "131000000", "129500000")
            seedMinutesData("bithumb", "btc", prevHourStart.plus(50, ChronoUnit.MINUTES), "130500000", "129200000")

            // when
            tickerAggregationJob.aggregateHour()

            // then
            val members = redisTemplate.opsForZSet().rangeWithScores("ticker:hours:bithumb:btc", 0, -1)
            assertThat(members).isNotEmpty
        }

        @Test
        fun `분 데이터를 집계하여 DB ticker_hour에 저장한다`() {
            // given
            val now = clock.instant()
            val prevHourStart = now.minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.HOURS)
            seedMinutesData("bithumb", "btc", prevHourStart.plus(10, ChronoUnit.MINUTES), "130000000", "129000000")
            seedMinutesData("bithumb", "btc", prevHourStart.plus(30, ChronoUnit.MINUTES), "131000000", "129500000")
            seedMinutesData("bithumb", "btc", prevHourStart.plus(50, ChronoUnit.MINUTES), "130500000", "129200000")

            // when
            tickerAggregationJob.aggregateHour()

            // then
            val result = aggregationRepository.findLatestHour("bithumb", "btc")
            assertThat(result).isNotNull
            assertThat(result!!.high).isEqualByComparingTo("131000000")
            assertThat(result.low).isEqualByComparingTo("129000000")
            assertThat(result.count).isEqualTo(30) // 3 entries * 10 count each
            val storedAt = jdbcTemplate.queryForObject(
                "SELECT hour_at FROM ticker_hour WHERE exchange = 'BITHUMB' AND symbol = 'BTC'",
                Timestamp::class.java,
            )
            assertThat(storedAt!!.toInstant()).isEqualTo(prevHourStart)
        }
    }

    @Nested
    @DisplayName("aggregateDay")
    inner class AggregateDay {

        @Test
        fun `시간 데이터를 집계하여 DB ticker_day에 저장한다`() {
            // given - 이전 일 윈도우에 데이터 seed
            val now = clock.instant()
            val prevDayStart = windowPolicy.previous(now, ChronoUnit.DAYS).from
            seedHoursData("bithumb", "btc", prevDayStart.plus(4, ChronoUnit.HOURS), "130000000", "129000000")
            seedHoursData("bithumb", "btc", prevDayStart.plus(12, ChronoUnit.HOURS), "132000000", "128000000")
            seedHoursData("bithumb", "btc", prevDayStart.plus(20, ChronoUnit.HOURS), "131000000", "128500000")

            // when
            tickerAggregationJob.aggregateDay()

            // then - day는 캐시 저장 없이 DB만
            val result = aggregationRepository.findLatestDay("bithumb", "btc")
            assertThat(result).isNotNull
            assertThat(result!!.high).isEqualByComparingTo("132000000")
            assertThat(result.low).isEqualByComparingTo("128000000")
            assertThat(result.count).isEqualTo(180) // 3 entries * 60 count each
            val storedDay = jdbcTemplate.queryForObject(
                "SELECT day_at FROM ticker_day WHERE exchange = 'BITHUMB' AND symbol = 'BTC'",
                java.sql.Date::class.java,
            )
            assertThat(storedDay!!.toLocalDate())
                .isEqualTo(prevDayStart.atZone(windowPolicy.zoneId).toLocalDate())
        }
    }
}
