package io.premiumspread.cache

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.premiumspread.client.TickerData
import io.premiumspread.domain.ticker.TickerSnapshot
import io.premiumspread.infrastructure.common.cache.AfterCommitCacheExecutor
import io.premiumspread.infrastructure.common.cache.CacheReadMetrics
import io.premiumspread.infrastructure.common.cache.ticker.TickerCacheReader
import io.premiumspread.infrastructure.common.cache.ticker.TickerCacheWriter
import io.premiumspread.redis.TickerAggregationTimeUnit
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

class TickerCacheServiceTest {

    private lateinit var redisTemplate: StringRedisTemplate
    private lateinit var hashOps: HashOperations<String, String, String>
    private lateinit var zSetOps: ZSetOperations<String, String>
    private lateinit var tickerCacheService: TickerCacheService

    @BeforeEach
    fun setUp() {
        hashOps = mockk(relaxed = true)
        zSetOps = mockk(relaxed = true)
        redisTemplate = mockk()
        every { redisTemplate.opsForHash<String, String>() } returns hashOps
        every { redisTemplate.opsForZSet() } returns zSetOps
        every { redisTemplate.expire(any(), any<Duration>()) } returns true
        val clock = Clock.fixed(Instant.parse("2026-05-12T00:00:00Z"), ZoneOffset.UTC)
        val timeSeriesCache = TimeSeriesCacheSupport(redisTemplate, clock)
        tickerCacheService = TickerCacheService(
            redisTemplate,
            timeSeriesCache,
            clock,
            TickerCacheReader(redisTemplate, CacheReadMetrics { _, _ -> }),
            TickerCacheWriter(redisTemplate, AfterCommitCacheExecutor()),
            CacheReadMetrics { _, _ -> },
        )
    }

    private fun tuple(value: String, score: Double): ZSetOperations.TypedTuple<String> =
        mockk<ZSetOperations.TypedTuple<String>>().also {
            every { it.value } returns value
            every { it.score } returns score
        }

    private fun bithumbTicker(
        price: String = "129555000",
        volume: String? = "1234.5678",
        epochMilli: Long = 1_706_500_000_000L,
    ) = TickerData(
        exchange = "BITHUMB",
        symbol = "BTC",
        currency = "KRW",
        price = BigDecimal(price),
        volume = volume?.let { BigDecimal(it) },
        timestamp = Instant.ofEpochMilli(epochMilli),
    )

    @Nested
    @DisplayName("save/get")
    inner class SaveGet {

        @Test
        fun `티커 데이터를 해시로 저장하고 TTL을 설정한다`() {
            // given
            val ticker = bithumbTicker()

            // when
            tickerCacheService.save(ticker)

            // then
            verify {
                hashOps.putAll(
                    "ticker:bithumb:btc",
                    match<Map<String, String>> { hash ->
                        hash["exchange"] == "BITHUMB" &&
                            hash["symbol"] == "BTC" &&
                            hash["price"] == "129555000" &&
                            hash["volume"] == "1234.5678"
                    },
                )
            }
            verify { redisTemplate.expire("ticker:bithumb:btc", any<Duration>()) }
        }

        @Test
        fun `해시가 존재하면 TickerData를 반환한다`() {
            // given
            every { hashOps.entries("ticker:bithumb:btc") } returns mapOf(
                "exchange" to "BITHUMB",
                "symbol" to "BTC",
                "currency" to "KRW",
                "price" to "129555000",
                "volume" to "1234.5678",
                "timestamp" to "1706500000000",
            )

            // when
            val result = tickerCacheService.get("bithumb", "btc")

            // then
            assertThat(result).isNotNull
            assertThat(result!!.price).isEqualByComparingTo("129555000")
            assertThat(result.volume).isEqualByComparingTo("1234.5678")
        }

        @Test
        fun `티커를 Domain snapshot으로 노출한다`() {
            every { hashOps.entries("ticker:bithumb:btc") } returns mapOf(
                "exchange" to "BITHUMB",
                "symbol" to "BTC",
                "currency" to "KRW",
                "price" to "129555000",
                "volume" to "1234.5678",
                "timestamp" to "1706500000000",
            )

            val result: TickerSnapshot? = tickerCacheService.getSnapshot("bithumb", "btc")

            assertThat(result).isEqualTo(
                TickerSnapshot(
                    exchange = "BITHUMB",
                    symbol = "BTC",
                    currency = "KRW",
                    price = BigDecimal("129555000"),
                    volume = BigDecimal("1234.5678"),
                    observedAt = Instant.ofEpochMilli(1_706_500_000_000L),
                ),
            )
        }

        @Test
        fun `volume이 빈 문자열이면 null로 반환한다`() {
            // given
            every { hashOps.entries("ticker:bithumb:btc") } returns mapOf(
                "exchange" to "BITHUMB",
                "symbol" to "BTC",
                "currency" to "KRW",
                "price" to "129555000",
                "volume" to "",
                "timestamp" to "1706500000000",
            )

            // when
            val result = tickerCacheService.get("bithumb", "btc")

            // then
            assertThat(result).isNotNull
            assertThat(result!!.volume).isNull()
        }

        @Test
        fun `해시가 없으면 null을 반환한다`() {
            // given
            every { hashOps.entries("ticker:bithumb:btc") } returns emptyMap()

            // when & then
            assertThat(tickerCacheService.get("bithumb", "btc")).isNull()
        }

        @Test
        fun `hash exchange가 요청 key와 다르면 손상 payload로 판단한다`() {
            every { hashOps.entries("ticker:bithumb:btc") } returns mapOf(
                "exchange" to "BINANCE",
                "symbol" to "BTC",
                "currency" to "KRW",
                "price" to "129555000",
                "timestamp" to "1706500000000",
            )

            assertThat(tickerCacheService.get("bithumb", "btc")).isNull()
        }

        @Test
        fun `hash symbol이 요청 key와 다르면 손상 payload로 판단한다`() {
            every { hashOps.entries("ticker:bithumb:btc") } returns mapOf(
                "exchange" to "BITHUMB",
                "symbol" to "ETH",
                "currency" to "KRW",
                "price" to "129555000",
                "timestamp" to "1706500000000",
            )

            assertThat(tickerCacheService.get("bithumb", "btc")).isNull()
        }

        @Test
        fun `price 필드가 없으면 null을 반환한다`() {
            // given
            every { hashOps.entries("ticker:bithumb:btc") } returns mapOf(
                "exchange" to "BITHUMB",
                "symbol" to "BTC",
                "currency" to "KRW",
                "volume" to "",
                "timestamp" to "1706500000000",
            )

            // when & then
            assertThat(tickerCacheService.get("bithumb", "btc")).isNull()
        }

        @Test
        fun `timestamp가 없으면 현재 시각을 합성하지 않고 null을 반환한다`() {
            every { hashOps.entries("ticker:bithumb:btc") } returns mapOf(
                "exchange" to "BITHUMB",
                "symbol" to "BTC",
                "currency" to "KRW",
                "price" to "129555000",
            )

            assertThat(tickerCacheService.get("bithumb", "btc")).isNull()
        }
    }

    @Nested
    @DisplayName("aggregateSecondsData")
    inner class AggregateSecondsData {

        @Test
        fun `초당 가격 데이터로 high, low, open, close, avg, count를 계산한다`() {
            // given
            val from = Instant.ofEpochMilli(0L)
            val to = Instant.ofEpochMilli(60_000L)
            every {
                zSetOps.rangeByScoreWithScores("ticker:seconds:bithumb:btc", any(), any())
            } returns linkedSetOf(
                tuple("129555000", 10_000.0), // open
                tuple("130000000", 30_000.0), // high
                tuple("129800000", 50_000.0), // close
            )

            // when
            val result = tickerCacheService.aggregateSecondsData("bithumb", "btc", "KRW", from, to)

            // then
            assertThat(result).isNotNull
            assertThat(result!!.high).isEqualByComparingTo("130000000")
            assertThat(result.low).isEqualByComparingTo("129555000")
            assertThat(result.open).isEqualByComparingTo("129555000")
            assertThat(result.close).isEqualByComparingTo("129800000")
            assertThat(result.count).isEqualTo(3)
            // avg = (129555000 + 130000000 + 129800000) / 3 = 129785000
            assertThat(result.avg).isEqualByComparingTo("129785000.0000")
        }

        @Test
        fun `데이터가 없으면 null을 반환한다`() {
            // given
            every {
                zSetOps.rangeByScoreWithScores(any(), any(), any())
            } returns emptySet()

            // when & then
            assertThat(
                tickerCacheService.aggregateSecondsData(
                    "bithumb",
                    "btc",
                    "KRW",
                    Instant.EPOCH,
                    Instant.EPOCH.plusMillis(1),
                ),
            ).isNull()
        }

        @Test
        fun `seconds row 하나라도 손상되면 부분 집계하지 않는다`() {
            every {
                zSetOps.rangeByScoreWithScores("ticker:seconds:bithumb:btc", any(), any())
            } returns linkedSetOf(
                tuple("1706500000000:129555000", 10_000.0),
                tuple("corrupt", 20_000.0),
            )

            assertThat(
                tickerCacheService.aggregateSecondsData(
                    "bithumb",
                    "btc",
                    "KRW",
                    Instant.EPOCH,
                    Instant.EPOCH.plusSeconds(60),
                ),
            ).isNull()
        }
    }

    @Nested
    @DisplayName("aggregateData (분→시간 재집계)")
    inner class AggregateData {

        @Test
        fun `분 집계 데이터를 재집계하여 high, low, count(합산)를 반환한다`() {
            // given - "high:low:open:close:avg:count" 포맷
            val from = Instant.ofEpochMilli(0L)
            val to = Instant.ofEpochMilli(3_600_000L)
            every {
                zSetOps.rangeByScoreWithScores("ticker:minutes:bithumb:btc", any(), any())
            } returns linkedSetOf(
                // agg1: high=130, low=129, avg=129.5, count=10
                tuple("130000000:129000000:129000000:130000000:129500000:10", 600_000.0),
                // agg2: high=131, low=129.5, avg=130.25, count=10
                tuple("131000000:129500000:129500000:131000000:130250000:10", 1_200_000.0),
            )

            // when
            val result = tickerCacheService.aggregateData(TickerAggregationTimeUnit.MINUTES, "bithumb", "btc", "KRW", from, to)

            // then
            assertThat(result).isNotNull
            assertThat(result!!.high).isEqualByComparingTo("131000000")
            assertThat(result.low).isEqualByComparingTo("129000000")
            assertThat(result.open).isEqualByComparingTo("129000000") // first.open
            assertThat(result.close).isEqualByComparingTo("131000000") // last.close
            assertThat(result.count).isEqualTo(20)
        }

        @Test
        fun `데이터가 없으면 null을 반환한다`() {
            // given
            every { zSetOps.rangeByScoreWithScores(any(), any(), any()) } returns emptySet()

            // when & then
            assertThat(
                tickerCacheService.aggregateData(
                    TickerAggregationTimeUnit.MINUTES,
                    "bithumb",
                    "btc",
                    "KRW",
                    Instant.EPOCH,
                    Instant.EPOCH.plusMillis(1),
                ),
            ).isNull()
        }

        @Test
        fun `value 포맷이 잘못된 엔트리가 있으면 부분 집계하지 않는다`() {
            // given
            every {
                zSetOps.rangeByScoreWithScores("ticker:minutes:bithumb:btc", any(), any())
            } returns linkedSetOf(
                tuple("invalid-format", 600_000.0),
                tuple("130000000:129000000:129000000:130000000:129500000:10", 1_200_000.0),
            )

            // when
            val result = tickerCacheService.aggregateData(
                TickerAggregationTimeUnit.MINUTES,
                "bithumb",
                "btc",
                "KRW",
                Instant.EPOCH,
                Instant.EPOCH.plusMillis(1),
            )

            assertThat(result).isNull()
        }
    }
}
