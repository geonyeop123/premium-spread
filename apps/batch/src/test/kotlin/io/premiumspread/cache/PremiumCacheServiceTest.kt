package io.premiumspread.cache

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.premiumspread.redis.AggregationTimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.HashOperations
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ZSetOperations
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

class PremiumCacheServiceTest {

    private lateinit var redisTemplate: StringRedisTemplate
    private lateinit var hashOps: HashOperations<String, String, String>
    private lateinit var zSetOps: ZSetOperations<String, String>
    private lateinit var premiumCacheService: PremiumCacheService

    @BeforeEach
    fun setUp() {
        hashOps = mockk(relaxed = true)
        zSetOps = mockk(relaxed = true)
        redisTemplate = mockk()
        every { redisTemplate.opsForHash<String, String>() } returns hashOps
        every { redisTemplate.opsForZSet() } returns zSetOps
        every { redisTemplate.expire(any(), any<Duration>()) } returns true
        premiumCacheService = PremiumCacheService(redisTemplate)
    }

    private fun tuple(value: String, score: Double): ZSetOperations.TypedTuple<String> =
        mockk<ZSetOperations.TypedTuple<String>>().also {
            every { it.value } returns value
            every { it.score } returns score
        }

    private fun premiumCacheData(
        rate: String = "1.50",
        koreaPrice: String = "129555000",
        foreignPrice: String = "89277.10",
        foreignPriceKrw: String = "127894943.46",
        fxRate: String = "1432.60",
        epochMilli: Long = 1_706_500_000_000L,
    ) = PremiumCacheData(
        symbol = "BTC",
        premiumRate = BigDecimal(rate),
        koreaPrice = BigDecimal(koreaPrice),
        foreignPrice = BigDecimal(foreignPrice),
        foreignPriceInKrw = BigDecimal(foreignPriceKrw),
        fxRate = BigDecimal(fxRate),
        observedAt = Instant.ofEpochMilli(epochMilli),
    )

    @Nested
    @DisplayName("save/get")
    inner class SaveGet {

        @Test
        fun `프리미엄 데이터를 해시로 저장하고 TTL을 설정한다`() {
            // given
            val premium = premiumCacheData()

            // when
            premiumCacheService.save(premium)

            // then
            verify {
                hashOps.putAll(
                    "premium:btc",
                    match<Map<String, String>> { hash ->
                        hash["symbol"] == "BTC" &&
                            hash["rate"] == "1.50" &&
                            hash["korea_price"] == "129555000" &&
                            hash["foreign_price"] == "89277.10" &&
                            hash["fx_rate"] == "1432.60"
                    },
                )
            }
            verify { redisTemplate.expire("premium:btc", any<Duration>()) }
        }

        @Test
        fun `해시가 존재하면 PremiumCacheData를 반환한다`() {
            // given
            every { hashOps.entries("premium:btc") } returns mapOf(
                "symbol" to "BTC",
                "rate" to "1.50",
                "korea_price" to "129555000",
                "foreign_price" to "89277.10",
                "foreign_price_krw" to "127894943.46",
                "fx_rate" to "1432.60",
                "observed_at" to "1706500000000",
            )

            // when
            val result = premiumCacheService.get("btc")

            // then
            assertThat(result).isNotNull
            assertThat(result!!.symbol).isEqualTo("BTC")
            assertThat(result.premiumRate).isEqualByComparingTo("1.50")
            assertThat(result.koreaPrice).isEqualByComparingTo("129555000")
            assertThat(result.fxRate).isEqualByComparingTo("1432.60")
        }

        @Test
        fun `해시가 없으면 null을 반환한다`() {
            // given
            every { hashOps.entries("premium:btc") } returns emptyMap()

            // when & then
            assertThat(premiumCacheService.get("btc")).isNull()
        }

        @Test
        fun `rate 필드가 숫자가 아니면 null을 반환한다`() {
            // given
            every { hashOps.entries("premium:btc") } returns mapOf(
                "symbol" to "BTC",
                "rate" to "invalid",
                "korea_price" to "129555000",
                "foreign_price" to "89277.10",
                "foreign_price_krw" to "127894943.46",
                "fx_rate" to "1432.60",
                "observed_at" to "1706500000000",
            )

            // when & then
            assertThat(premiumCacheService.get("btc")).isNull()
        }
    }

    @Nested
    @DisplayName("aggregateSecondsData")
    inner class AggregateSecondsData {

        @Test
        fun `초당 rate 데이터로 high, low, open, close, avg, count를 계산한다`() {
            // given - seconds value 포맷: "rate:koreaPrice:foreignPrice:fxRate"
            val from = Instant.ofEpochMilli(0L)
            val to = Instant.ofEpochMilli(60_000L)
            every {
                zSetOps.rangeByScoreWithScores("premium:seconds:btc", any(), any())
            } returns linkedSetOf(
                tuple("1.50:129555000:89277.10:1432.60", 10_000.0), // open
                tuple("2.00:130000000:89277.10:1432.60", 30_000.0), // high
                tuple("1.80:129800000:89277.10:1432.60", 50_000.0), // close
            )

            // when
            val result = premiumCacheService.aggregateSecondsData("btc", from, to)

            // then
            assertThat(result).isNotNull
            assertThat(result!!.high).isEqualByComparingTo("2.00")
            assertThat(result.low).isEqualByComparingTo("1.50")
            assertThat(result.open).isEqualByComparingTo("1.50")
            assertThat(result.close).isEqualByComparingTo("1.80")
            assertThat(result.count).isEqualTo(3)
            // avg = (1.50 + 2.00 + 1.80) / 3 = 1.7667
            assertThat(result.avg).isEqualByComparingTo("1.7667")
        }

        @Test
        fun `데이터가 없으면 null을 반환한다`() {
            // given
            every { zSetOps.rangeByScoreWithScores(any(), any(), any()) } returns emptySet()

            // when & then
            assertThat(
                premiumCacheService.aggregateSecondsData("btc", Instant.now(), Instant.now()),
            ).isNull()
        }
    }

    @Nested
    @DisplayName("calculateSummaryFromSeconds")
    inner class CalculateSummaryFromSeconds {

        @Test
        fun `초당 데이터로 high, low, current(마지막)를 계산한다`() {
            // given
            val from = Instant.ofEpochMilli(0L)
            val to = Instant.ofEpochMilli(60_000L)
            every {
                zSetOps.rangeByScoreWithScores("premium:seconds:btc", any(), any())
            } returns linkedSetOf(
                tuple("1.50:129555000:89277.10:1432.60", 10_000.0),
                tuple("2.00:130000000:89277.10:1432.60", 30_000.0),
                tuple("1.80:129800000:89277.10:1432.60", 50_000.0), // last → current
            )

            // when
            val result = premiumCacheService.calculateSummaryFromSeconds("btc", from, to)

            // then
            assertThat(result).isNotNull
            assertThat(result!!.high).isEqualByComparingTo("2.00")
            assertThat(result.low).isEqualByComparingTo("1.50")
            assertThat(result.current).isEqualByComparingTo("1.80") // 마지막 rate
            assertThat(result.currentTimestamp).isEqualTo(Instant.ofEpochMilli(50_000L))
        }

        @Test
        fun `데이터가 없으면 null을 반환한다`() {
            // given
            every { zSetOps.rangeByScoreWithScores(any(), any(), any()) } returns emptySet()

            // when & then
            assertThat(
                premiumCacheService.calculateSummaryFromSeconds("btc", Instant.now(), Instant.now()),
            ).isNull()
        }
    }

    @Nested
    @DisplayName("calculateSummary (집계 데이터 기반)")
    inner class CalculateSummary {

        @Test
        fun `분 집계 데이터로 high, low, current(마지막 close)를 계산한다`() {
            // given - "high:low:open:close:avg:count" 포맷
            val from = Instant.ofEpochMilli(0L)
            val to = Instant.ofEpochMilli(3_600_000L)
            every {
                zSetOps.rangeByScoreWithScores("premium:minutes:btc", any(), any())
            } returns linkedSetOf(
                // agg1: high=2.50, low=1.50, open=1.50, close=2.50, avg=2.00, count=10
                tuple("2.50:1.50:1.50:2.50:2.00:10", 600_000.0),
                // agg2: high=3.00, low=2.00, open=2.00, close=2.80, avg=2.50, count=10
                tuple("3.00:2.00:2.00:2.80:2.50:10", 1_200_000.0),
            )

            // when
            val result = premiumCacheService.calculateSummary(AggregationTimeUnit.MINUTES, "btc", from, to)

            // then
            assertThat(result).isNotNull
            assertThat(result!!.high).isEqualByComparingTo("3.00") // max of highs
            assertThat(result.low).isEqualByComparingTo("1.50")   // min of lows
            assertThat(result.current).isEqualByComparingTo("2.80") // last agg.close
            assertThat(result.currentTimestamp).isEqualTo(Instant.ofEpochMilli(1_200_000L))
        }

        @Test
        fun `데이터가 없으면 null을 반환한다`() {
            // given
            every { zSetOps.rangeByScoreWithScores(any(), any(), any()) } returns emptySet()

            // when & then
            assertThat(
                premiumCacheService.calculateSummary(AggregationTimeUnit.MINUTES, "btc", Instant.now(), Instant.now()),
            ).isNull()
        }
    }
}
