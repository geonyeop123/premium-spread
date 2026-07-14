package io.premiumspread.cache

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.premiumspread.domain.market.MarketPair
import io.premiumspread.domain.premium.PremiumSnapshot
import io.premiumspread.domain.ticker.Exchange
import io.premiumspread.domain.ticker.Symbol
import io.premiumspread.infrastructure.common.cache.AfterCommitCacheExecutor
import io.premiumspread.infrastructure.common.cache.CacheReadMetrics
import io.premiumspread.infrastructure.common.cache.premium.PremiumCacheReader
import io.premiumspread.infrastructure.common.cache.premium.PremiumCacheWriter
import io.premiumspread.infrastructure.common.cache.premium.PremiumAggregationCacheReader
import io.premiumspread.redis.AggregationTimeUnit
import io.premiumspread.redis.RedisTtl
import io.premiumspread.redis.support.TimeSeriesCacheSupport
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
import java.time.Clock
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit

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
        every { redisTemplate.getExpire(any(), TimeUnit.MILLISECONDS) } returns -1L
        val clock = Clock.fixed(Instant.parse("2026-05-12T00:00:00Z"), ZoneOffset.UTC)
        val timeSeriesCache = TimeSeriesCacheSupport(redisTemplate, clock)
        val metrics = CacheReadMetrics { _, _ -> }
        premiumCacheService = PremiumCacheService(
            redisTemplate,
            timeSeriesCache,
            clock,
            PremiumCacheReader(redisTemplate, metrics),
            PremiumCacheWriter(redisTemplate, timeSeriesCache, AfterCommitCacheExecutor()),
            PremiumAggregationCacheReader(redisTemplate, metrics),
            metrics,
        )
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
        premiumRate = BigDecimal(rate),
        koreaPrice = BigDecimal(koreaPrice),
        foreignPrice = BigDecimal(foreignPrice),
        foreignPriceInKrw = BigDecimal(foreignPriceKrw),
        fxRate = BigDecimal(fxRate),
        observedAt = Instant.ofEpochMilli(epochMilli),
        pair = MarketPair.default(Symbol("BTC")),
    )

    private fun premiumSnapshot(
        symbol: String = "BTC",
        koreaExchange: Exchange = Exchange.BITHUMB,
        foreignExchange: Exchange = Exchange.BINANCE,
        observedAt: Instant = Instant.ofEpochMilli(1_706_500_000_000L),
        fxObservedAt: Instant = Instant.ofEpochMilli(1_706_499_999_000L),
    ) = PremiumSnapshot(
        pair = MarketPair(Symbol(symbol), koreaExchange, foreignExchange),
        premiumRate = BigDecimal("1.50"),
        koreaPrice = BigDecimal("129555000"),
        foreignPrice = BigDecimal("89277.10"),
        foreignPriceInKrw = BigDecimal("127894943.46"),
        fxRate = BigDecimal("1432.60"),
        observedAt = observedAt,
        fxSource = Exchange.FX_PROVIDER,
        fxObservedAt = fxObservedAt,
    )

    @Nested
    @DisplayName("save/get")
    inner class SaveGet {

        @Test
        fun `프리미엄 데이터를 해시로 저장하고 TTL을 설정한다`() {
            // given
            val premium = premiumSnapshot()

            // when
            premiumCacheService.save(premium)

            // then
            verify {
                hashOps.putAll(
                    "premium:bithumb:binance:btc",
                    match<Map<String, String>> { hash ->
                        hash["symbol"] == "BTC" &&
                            hash["rate"] == "1.50" &&
                            hash["korea_price"] == "129555000" &&
                            hash["foreign_price"] == "89277.10" &&
                            hash["fx_rate"] == "1432.60" &&
                            hash["korea_exchange"] == "BITHUMB" &&
                            hash["foreign_exchange"] == "BINANCE" &&
                            hash["fx_source"] == "FX_PROVIDER" &&
                            hash["fx_observed_at"] == "1706499999000"
                    },
                )
            }
            verify { redisTemplate.expire("premium:bithumb:binance:btc", any<Duration>()) }
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
            assertThat(result.pair).isEqualTo(MarketPair.default(Symbol("BTC")))
            assertThat(result.fxSource).isEqualTo(Exchange.FX_PROVIDER)
            assertThat(result.fxObservedAt).isEqualTo(result.observedAt)
        }

        @Test
        fun `메타데이터가 모두 있으면 pair와 FX 원본 정보를 복원한다`() {
            val pair = MarketPair(Symbol("BTC"), Exchange.UPBIT, Exchange.BINANCE)
            every { hashOps.entries("premium:upbit:binance:btc") } returns mapOf(
                "schema_version" to "2",
                "symbol" to "BTC",
                "rate" to "1.50",
                "korea_price" to "129555000",
                "foreign_price" to "89277.10",
                "foreign_price_krw" to "127894943.46",
                "fx_rate" to "1432.60",
                "observed_at" to "1706500000000",
                "korea_exchange" to "UPBIT",
                "foreign_exchange" to "BINANCE",
                "fx_source" to "FX_PROVIDER",
                "fx_observed_at" to "1706499999000",
            )

            val result = premiumCacheService.get(pair)

            assertThat(result).isNotNull
            assertThat(result!!.pair).isEqualTo(pair)
            assertThat(result.fxSource).isEqualTo(Exchange.FX_PROVIDER)
            assertThat(result.fxObservedAt).isEqualTo(Instant.ofEpochMilli(1_706_499_999_000L))
        }

        @Test
        fun `메타데이터가 일부만 있으면 손상 payload로 판단한다`() {
            every { hashOps.entries("premium:btc") } returns mapOf(
                "symbol" to "BTC",
                "rate" to "1.50",
                "korea_price" to "129555000",
                "foreign_price" to "89277.10",
                "foreign_price_krw" to "127894943.46",
                "fx_rate" to "1432.60",
                "observed_at" to "1706500000000",
                "korea_exchange" to "BITHUMB",
            )

            assertThat(premiumCacheService.get("btc")).isNull()
        }

        @Test
        fun `메타데이터 enum이 유효하지 않으면 손상 payload로 판단한다`() {
            every { hashOps.entries("premium:btc") } returns mapOf(
                "symbol" to "BTC",
                "rate" to "1.50",
                "korea_price" to "129555000",
                "foreign_price" to "89277.10",
                "foreign_price_krw" to "127894943.46",
                "fx_rate" to "1432.60",
                "observed_at" to "1706500000000",
                "korea_exchange" to "INVALID",
                "foreign_exchange" to "BINANCE",
                "fx_source" to "FX_PROVIDER",
                "fx_observed_at" to "1706499999000",
            )

            assertThat(premiumCacheService.get("btc")).isNull()
        }

        @Test
        fun `v2 writer는 non-default pair를 분리 저장한다`() {
            val premium = premiumSnapshot(koreaExchange = Exchange.UPBIT)

            premiumCacheService.save(premium)

            verify { hashOps.putAll("premium:upbit:binance:btc", any()) }
        }

        @Test
        fun `v2 history writer는 non-default pair를 분리 저장한다`() {
            val premium = premiumSnapshot(koreaExchange = Exchange.UPBIT)

            premiumCacheService.saveHistory(premium)

            verify { zSetOps.add("premium:upbit:binance:btc:history", any(), any()) }
        }

        @Test
        fun `v2 seconds writer는 non-default pair를 분리 저장한다`() {
            val premium = premiumSnapshot(koreaExchange = Exchange.UPBIT)

            premiumCacheService.saveToSeconds(premium)

            verify { zSetOps.add("premium:upbit:binance:btc:seconds", any(), any()) }
        }

        @Test
        fun `요청 symbol과 저장된 symbol이 다르면 손상 payload로 판단한다`() {
            every { hashOps.entries("premium:btc") } returns mapOf(
                "symbol" to "ETH",
                "rate" to "1.50",
                "korea_price" to "129555000",
                "foreign_price" to "89277.10",
                "foreign_price_krw" to "127894943.46",
                "fx_rate" to "1432.60",
                "observed_at" to "1706500000000",
            )

            assertThat(premiumCacheService.get("btc")).isNull()
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

        @Test
        fun `observed_at이 없으면 현재 시각을 합성하지 않고 null을 반환한다`() {
            every { hashOps.entries("premium:btc") } returns mapOf(
                "symbol" to "BTC",
                "rate" to "1.50",
                "korea_price" to "129555000",
                "foreign_price" to "89277.10",
                "foreign_price_krw" to "127894943.46",
                "fx_rate" to "1432.60",
            )

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
                zSetOps.rangeByScoreWithScores("premium:bithumb:binance:btc:seconds", any(), any())
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
                premiumCacheService.aggregateSecondsData("btc", Instant.EPOCH, Instant.EPOCH.plusMillis(1)),
            ).isNull()
        }

        @Test
        fun `default pair는 v2 seconds miss 시 legacy를 읽고 TTL을 축소한다`() {
            val from = Instant.EPOCH
            val to = from.plusSeconds(60)
            every {
                zSetOps.rangeByScoreWithScores("premium:bithumb:binance:btc:seconds", any(), any())
            } returns emptySet()
            every {
                zSetOps.rangeByScoreWithScores("premium:seconds:btc", any(), any())
            } returns linkedSetOf(tuple("1.50:100:90:1432.60", 10_000.0))
            every { redisTemplate.getExpire("premium:seconds:btc", TimeUnit.MILLISECONDS) } returns -1L

            val result = premiumCacheService.aggregateSecondsData("btc", from, to)

            assertThat(result?.count).isEqualTo(1)
            verify { redisTemplate.expire("premium:seconds:btc", RedisTtl.PREMIUM_LEGACY_READ_WINDOW) }
        }

        @Test
        fun `seconds row 하나라도 손상되면 부분 집계하지 않는다`() {
            every {
                zSetOps.rangeByScoreWithScores("premium:bithumb:binance:btc:seconds", any(), any())
            } returns linkedSetOf(
                tuple("1.50:100:90:1432.60", 10_000.0),
                tuple("1.60:100:90:broken", 20_000.0),
            )

            assertThat(
                premiumCacheService.aggregateSecondsData("btc", Instant.EPOCH, Instant.EPOCH.plusSeconds(60)),
            ).isNull()
        }

        @Test
        fun `non-default pair는 v2 seconds miss여도 legacy를 조회하지 않는다`() {
            val pair = MarketPair(Symbol("BTC"), Exchange.UPBIT, Exchange.BINANCE)
            every {
                zSetOps.rangeByScoreWithScores("premium:upbit:binance:btc:seconds", any(), any())
            } returns emptySet()

            val result = premiumCacheService.getSecondsDataFull(pair, Instant.EPOCH, Instant.EPOCH.plusSeconds(60))

            assertThat(result).isEmpty()
            verify(exactly = 0) { zSetOps.rangeByScoreWithScores("premium:seconds:btc", any(), any()) }
        }
    }

    @Nested
    @DisplayName("summary cache")
    inner class SummaryCache {
        private val summary = mapOf(
            "high" to "2.0",
            "low" to "1.0",
            "current" to "1.5",
            "current_ts" to "1706500000000",
            "updated_at" to "1706500001000",
        )

        @Test
        fun `default pair는 v2 summary miss 시 legacy를 읽고 TTL을 축소한다`() {
            every { hashOps.entries("premium:bithumb:binance:btc:summary:1m") } returns emptyMap()
            every { hashOps.entries("summary:1m:btc") } returns summary

            val result = premiumCacheService.getSummary("1m", "btc")

            assertThat(result?.current).isEqualByComparingTo("1.5")
            verify { redisTemplate.expire("summary:1m:btc", RedisTtl.PREMIUM_LEGACY_READ_WINDOW) }
        }

        @Test
        fun `non-default pair는 v2 summary miss여도 legacy를 조회하지 않는다`() {
            val pair = MarketPair(Symbol("BTC"), Exchange.UPBIT, Exchange.BINANCE)
            every { hashOps.entries("premium:upbit:binance:btc:summary:1m") } returns emptyMap()

            assertThat(premiumCacheService.getSummary("1m", pair)).isNull()
            verify(exactly = 0) { hashOps.entries("summary:1m:btc") }
        }

        @Test
        fun `summary 필드 하나라도 손상되면 부분 payload를 반환하지 않는다`() {
            every { hashOps.entries("premium:bithumb:binance:btc:summary:1m") } returns
                summary + ("current" to "corrupt")

            assertThat(premiumCacheService.getSummary("1m", "btc")).isNull()
            verify(exactly = 0) { hashOps.entries("summary:1m:btc") }
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
                zSetOps.rangeByScoreWithScores("premium:bithumb:binance:btc:seconds", any(), any())
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
                premiumCacheService.calculateSummaryFromSeconds("btc", Instant.EPOCH, Instant.EPOCH.plusMillis(1)),
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
                zSetOps.rangeByScoreWithScores("premium:bithumb:binance:btc:minutes", any(), any())
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
                premiumCacheService.calculateSummary(
                    AggregationTimeUnit.MINUTES,
                    "btc",
                    Instant.EPOCH,
                    Instant.EPOCH.plusMillis(1),
                ),
            ).isNull()
        }
    }
}
