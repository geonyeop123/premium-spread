package io.premiumspread.scheduler

import io.premiumspread.domain.market.MarketPair
import io.premiumspread.domain.ticker.Symbol
import io.premiumspread.infrastructure.common.persistence.jdbc.premium.PremiumAggregationRepository
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

@DisplayName("PremiumAggregation E2E")
class PremiumAggregationE2ETest : BatchIntegrationTestBase() {

    private val pair = MarketPair.default(Symbol("BTC"))

    @Autowired
    lateinit var premiumAggregationScheduler: PremiumAggregationScheduler

    @Autowired
    lateinit var aggregationRepository: PremiumAggregationRepository

    @Autowired
    lateinit var clock: Clock

    @Autowired
    lateinit var windowPolicy: AggregationWindowPolicy

    /**
     * pair-aware v2 seconds ZSet에 초당 데이터 seed
     * value format: "rate:koreaPrice:foreignPrice:fxRate"
     */
    private fun seedSecondsData(timestamp: Instant, rate: String) {
        val key = "premium:bithumb:binance:btc:seconds"
        val value = "$rate:129555000:89277.10:1432.60"
        redisTemplate.opsForZSet().add(key, value, timestamp.toEpochMilli().toDouble())
    }

    /**
     * pair-aware v2 minutes ZSet에 분 집계 데이터 seed
     * value format: "high:low:open:close:avg:count"
     */
    private fun seedMinutesData(timestamp: Instant, high: String, low: String) {
        val key = "premium:bithumb:binance:btc:minutes"
        val value = "$high:$low:$low:$high:${(high.toBigDecimal() + low.toBigDecimal()) / 2.toBigDecimal()}:10"
        redisTemplate.opsForZSet().add(key, value, timestamp.toEpochMilli().toDouble())
    }

    /**
     * pair-aware v2 hours ZSet에 시간 집계 데이터 seed
     * value format: "high:low:open:close:avg:count"
     */
    private fun seedHoursData(timestamp: Instant, high: String, low: String) {
        val key = "premium:bithumb:binance:btc:hours"
        val value = "$high:$low:$low:$high:${(high.toBigDecimal() + low.toBigDecimal()) / 2.toBigDecimal()}:60"
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
            seedSecondsData(prevMinuteStart.plusSeconds(10), "1.50")
            seedSecondsData(prevMinuteStart.plusSeconds(30), "2.00")
            seedSecondsData(prevMinuteStart.plusSeconds(50), "1.80")

            // when
            premiumAggregationScheduler.aggregateMinute()

            // then - 분 캐시 검증
            val members = redisTemplate.opsForZSet().rangeWithScores("premium:bithumb:binance:btc:minutes", 0, -1)
            assertThat(members).isNotEmpty
        }

        @Test
        fun `초당 데이터를 집계하여 DB premium_minute에 저장한다`() {
            // given
            val now = clock.instant()
            val prevMinuteStart = now.minus(1, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MINUTES)
            seedSecondsData(prevMinuteStart.plusSeconds(10), "1.50")
            seedSecondsData(prevMinuteStart.plusSeconds(30), "2.00")
            seedSecondsData(prevMinuteStart.plusSeconds(50), "1.80")

            // when
            premiumAggregationScheduler.aggregateMinute()

            // then - DB 검증
            val result = aggregationRepository.findLatestMinute(pair)
            assertThat(result).isNotNull
            assertThat(result!!.high).isEqualByComparingTo("2.00")
            assertThat(result.low).isEqualByComparingTo("1.50")
            assertThat(result.open).isEqualByComparingTo("1.50")
            assertThat(result.close).isEqualByComparingTo("1.80")
            assertThat(result.count).isEqualTo(3)
            val storedAt = jdbcTemplate.queryForObject(
                "SELECT minute_at FROM premium_minute WHERE symbol = 'BTC'",
                Timestamp::class.java,
            )
            assertThat(storedAt!!.toInstant()).isEqualTo(prevMinuteStart)
        }

        @Test
        fun `소스 데이터 없으면 DB에 저장하지 않는다`() {
            // given - 소스 데이터 없음 (cleanUp에서 Redis 비워짐)

            // when
            premiumAggregationScheduler.aggregateMinute()

            // then
            assertThat(aggregationRepository.findLatestMinute(pair)).isNull()
            assertThat(redisTemplate.opsForZSet().size("premium:bithumb:binance:btc:minutes")).isEqualTo(0)
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
            seedMinutesData(prevHourStart.plus(10, ChronoUnit.MINUTES), "2.50", "1.50")
            seedMinutesData(prevHourStart.plus(30, ChronoUnit.MINUTES), "3.00", "2.00")
            seedMinutesData(prevHourStart.plus(50, ChronoUnit.MINUTES), "2.80", "1.80")

            // when
            premiumAggregationScheduler.aggregateHour()

            // then
            val members = redisTemplate.opsForZSet().rangeWithScores("premium:bithumb:binance:btc:hours", 0, -1)
            assertThat(members).isNotEmpty
        }

        @Test
        fun `분 데이터를 집계하여 DB premium_hour에 저장한다`() {
            // given
            val now = clock.instant()
            val prevHourStart = now.minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.HOURS)
            seedMinutesData(prevHourStart.plus(10, ChronoUnit.MINUTES), "2.50", "1.50")
            seedMinutesData(prevHourStart.plus(30, ChronoUnit.MINUTES), "3.00", "2.00")
            seedMinutesData(prevHourStart.plus(50, ChronoUnit.MINUTES), "2.80", "1.80")

            // when
            premiumAggregationScheduler.aggregateHour()

            // then
            val result = aggregationRepository.findLatestHour(pair)
            assertThat(result).isNotNull
            assertThat(result!!.high).isEqualByComparingTo("3.00")
            assertThat(result.low).isEqualByComparingTo("1.50")
            assertThat(result.count).isEqualTo(30) // 3 entries * 10 count each
            val storedAt = jdbcTemplate.queryForObject(
                "SELECT hour_at FROM premium_hour WHERE symbol = 'BTC'",
                Timestamp::class.java,
            )
            assertThat(storedAt!!.toInstant()).isEqualTo(prevHourStart)
        }

        @Test
        fun `소스 데이터 없으면 DB에 저장하지 않는다`() {
            // given - 소스 데이터 없음

            // when
            premiumAggregationScheduler.aggregateHour()

            // then
            assertThat(aggregationRepository.findLatestHour(pair)).isNull()
        }
    }

    @Nested
    @DisplayName("aggregateDay")
    inner class AggregateDay {

        @Test
        fun `시간 데이터를 집계하여 DB와 Redis에 저장한다`() {
            // given - 이전 일 윈도우에 데이터 seed
            val now = clock.instant()
            val prevDayStart = windowPolicy.previous(now, ChronoUnit.DAYS).from
            seedHoursData(prevDayStart.plus(4, ChronoUnit.HOURS), "2.50", "1.50")
            seedHoursData(prevDayStart.plus(12, ChronoUnit.HOURS), "3.50", "1.00")
            seedHoursData(prevDayStart.plus(20, ChronoUnit.HOURS), "2.80", "1.80")

            // when
            premiumAggregationScheduler.aggregateDay()

            // then - DB 검증
            val result = aggregationRepository.findLatestDay(pair)
            assertThat(result).isNotNull
            assertThat(result!!.high).isEqualByComparingTo("3.50")
            assertThat(result.low).isEqualByComparingTo("1.00")
            assertThat(result.count).isEqualTo(180) // 3 entries * 60 count each
            val storedDay = jdbcTemplate.queryForObject(
                "SELECT day_at FROM premium_day WHERE symbol = 'BTC'",
                java.sql.Date::class.java,
            )
            assertThat(storedDay!!.toLocalDate())
                .isEqualTo(prevDayStart.atZone(windowPolicy.zoneId).toLocalDate())

            // then - Redis 검증
            val entries = redisTemplate.opsForZSet().rangeWithScores("premium:bithumb:binance:btc:days", 0, -1)
            assertThat(entries).isNotEmpty
        }
    }

    @Nested
    @DisplayName("updateSummaryCache")
    inner class UpdateSummaryCache {

        @Test
        fun `소스 데이터 없으면 summary 캐시가 저장되지 않는다`() {
            // given - 소스 데이터 없음

            // when
            premiumAggregationScheduler.updateSummaryCache()

            // then - 4개 구간 모두 키 없음
            assertThat(redisTemplate.opsForHash<String, String>().entries("premium:bithumb:binance:btc:summary:1m")).isEmpty()
            assertThat(redisTemplate.opsForHash<String, String>().entries("premium:bithumb:binance:btc:summary:10m")).isEmpty()
            assertThat(redisTemplate.opsForHash<String, String>().entries("premium:bithumb:binance:btc:summary:1h")).isEmpty()
            assertThat(redisTemplate.opsForHash<String, String>().entries("premium:bithumb:binance:btc:summary:1d")).isEmpty()
        }

        @Test
        fun `모든 구간의 서머리 캐시가 저장된다`() {
            // given - 각 구간별 소스 데이터 seed
            val now = clock.instant()

            // 1m, 10m → seconds 데이터
            seedSecondsData(now.minusSeconds(30), "1.50")
            seedSecondsData(now.minusSeconds(15), "2.00")

            // 1h → minutes 데이터
            val prevMinute = now.minus(30, ChronoUnit.MINUTES)
            seedMinutesData(prevMinute, "3.00", "1.00")

            // 1d → hours 데이터
            val prevHour = now.minus(12, ChronoUnit.HOURS)
            seedHoursData(prevHour, "4.00", "0.50")

            // when
            premiumAggregationScheduler.updateSummaryCache()

            // then - 1m summary
            val summary1m = redisTemplate.opsForHash<String, String>().entries("premium:bithumb:binance:btc:summary:1m")
            assertThat(summary1m).isNotEmpty
            assertThat(summary1m["high"]).isNotNull()
            assertThat(summary1m["low"]).isNotNull()

            // then - 10m summary
            val summary10m = redisTemplate.opsForHash<String, String>().entries("premium:bithumb:binance:btc:summary:10m")
            assertThat(summary10m).isNotEmpty

            // then - 1h summary
            val summary1h = redisTemplate.opsForHash<String, String>().entries("premium:bithumb:binance:btc:summary:1h")
            assertThat(summary1h).isNotEmpty

            // then - 1d summary
            val summary1d = redisTemplate.opsForHash<String, String>().entries("premium:bithumb:binance:btc:summary:1d")
            assertThat(summary1d).isNotEmpty
        }
    }
}
