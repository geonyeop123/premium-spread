package io.premiumspread.infrastructure.common.cache.premium

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.premiumspread.domain.market.MarketPair
import io.premiumspread.domain.premium.PremiumSnapshot
import io.premiumspread.domain.ticker.Exchange
import io.premiumspread.domain.ticker.Symbol
import io.premiumspread.infrastructure.common.cache.AfterCommitCacheExecutor
import io.premiumspread.infrastructure.common.cache.CacheReadMetrics
import io.premiumspread.infrastructure.common.cache.CacheReadOutcome
import io.premiumspread.redis.RedisTtl
import io.premiumspread.redis.support.TimeSeriesCacheSupport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.HashOperations
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ZSetOperations
import org.springframework.data.redis.RedisConnectionFailureException
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

class PremiumCacheCutoverTest {
    private val redisTemplate = mockk<StringRedisTemplate>()
    private val hashOps = mockk<HashOperations<String, String, String>>(relaxed = true)
    private val metrics = mockk<CacheReadMetrics>(relaxed = true)
    private lateinit var reader: PremiumCacheReader

    private val defaultPair = MarketPair.default(Symbol("BTC"))
    private val upbitPair = MarketPair(Symbol("BTC"), Exchange.UPBIT, Exchange.BINANCE)

    @BeforeEach
    fun setUp() {
        every { redisTemplate.opsForHash<String, String>() } returns hashOps
        every { redisTemplate.expire(any(), any<Duration>()) } returns true
        every { redisTemplate.getExpire(any(), TimeUnit.MILLISECONDS) } returns -1L
        reader = PremiumCacheReader(redisTemplate, metrics)
    }

    @Test
    fun `v2 hit이면 legacy key를 조회하지 않는다`() {
        every { hashOps.entries("premium:bithumb:binance:btc") } returns v2Payload(defaultPair)

        val result = reader.get(defaultPair)

        assertThat(result?.pair).isEqualTo(defaultPair)
        verify(exactly = 0) { hashOps.entries("premium:btc") }
        verify { metrics.record("premium", CacheReadOutcome.HIT) }
    }

    @Test
    fun `v2 payload가 손상되면 legacy 값으로 숨기지 않는다`() {
        every { hashOps.entries("premium:bithumb:binance:btc") } returns v2Payload(defaultPair) + ("observed_at" to "bad")

        assertThat(reader.get(defaultPair)).isNull()
        verify(exactly = 0) { hashOps.entries("premium:btc") }
        verify { metrics.record("premium", CacheReadOutcome.CORRUPT) }
    }

    @Test
    fun `default pair는 v2 miss일 때 unversioned legacy를 읽는다`() {
        every { hashOps.entries("premium:bithumb:binance:btc") } returns emptyMap()
        every { hashOps.entries("premium:btc") } returns legacyPayload()

        assertThat(reader.get(defaultPair)?.premiumRate).isEqualByComparingTo("1.50")
        verify { redisTemplate.expire("premium:btc", RedisTtl.PREMIUM_LEGACY_READ_WINDOW) }
        verify { metrics.record("premium", CacheReadOutcome.MISS) }
        verify { metrics.record("premium", CacheReadOutcome.LEGACY_HIT) }
    }

    @Test
    fun `non-default pair는 symbol-only legacy로 fallback하지 않는다`() {
        every { hashOps.entries("premium:upbit:binance:btc") } returns emptyMap()

        assertThat(reader.get(upbitPair)).isNull()
        verify(exactly = 0) { hashOps.entries("premium:btc") }
    }

    @Test
    fun `legacy history hit은 cutover TTL로 축소한다`() {
        val zSetOps = mockk<ZSetOperations<String, String>>()
        val tuple = mockk<ZSetOperations.TypedTuple<String>>().also {
            every { it.value } returns "1.5:100:90"
            every { it.score } returns 1_706_500_000_000.0
        }
        every { redisTemplate.opsForZSet() } returns zSetOps
        every { zSetOps.reverseRangeWithScores("premium:bithumb:binance:btc:history", 0, 99) } returns emptySet()
        every { zSetOps.reverseRangeWithScores("premium:btc:history", 0, 99) } returns setOf(tuple)

        assertThat(reader.getHistory(defaultPair)).hasSize(1)
        verify { redisTemplate.expire("premium:btc:history", RedisTtl.PREMIUM_LEGACY_READ_WINDOW) }
    }

    @Test
    fun `legacy 반복 조회는 cutover TTL을 연장하지 않는다`() {
        every { hashOps.entries("premium:bithumb:binance:btc") } returns emptyMap()
        every { hashOps.entries("premium:btc") } returns legacyPayload()
        every { redisTemplate.getExpire("premium:btc", TimeUnit.MILLISECONDS) } returnsMany listOf(10_000L, 3_000L)

        reader.get(defaultPair)
        reader.get(defaultPair)

        verify(exactly = 1) { redisTemplate.expire("premium:btc", RedisTtl.PREMIUM_LEGACY_READ_WINDOW) }
    }

    @Test
    fun `history row 하나라도 손상되면 부분 결과를 반환하지 않는다`() {
        val zSetOps = mockk<ZSetOperations<String, String>>()
        every { redisTemplate.opsForZSet() } returns zSetOps
        every {
            zSetOps.reverseRangeWithScores("premium:bithumb:binance:btc:history", 0, 99)
        } returns setOf(
            mockk<ZSetOperations.TypedTuple<String>>().also {
                every { it.value } returns "1.5:100:90"
                every { it.score } returns 1_706_500_000_000.0
            },
            mockk<ZSetOperations.TypedTuple<String>>().also {
                every { it.value } returns "corrupt"
                every { it.score } returns 1_706_500_001_000.0
            },
        )

        assertThat(reader.getHistory(defaultPair)).isEmpty()
        verify { metrics.record("premium_history", CacheReadOutcome.CORRUPT) }
    }

    @Test
    fun `Redis 장애는 error로 기록하고 DB fallback을 위해 null을 반환한다`() {
        every { hashOps.entries("premium:bithumb:binance:btc") } throws RedisConnectionFailureException("down")

        assertThat(reader.get(defaultPair)).isNull()
        verify { metrics.record("premium", CacheReadOutcome.ERROR) }
    }

    @Test
    fun `writer는 versioned v2 key만 갱신한다`() {
        val writer = PremiumCacheWriter(
            redisTemplate,
            mockk<TimeSeriesCacheSupport>(relaxed = true),
            AfterCommitCacheExecutor(),
        )

        writer.save(snapshot(defaultPair))

        verify {
            hashOps.putAll(
                "premium:bithumb:binance:btc",
                match<Map<String, String>> {
                    it["schema_version"] == "2" &&
                        it["korea_exchange"] == "BITHUMB" &&
                        it["foreign_exchange"] == "BINANCE"
                },
            )
            redisTemplate.expire("premium:bithumb:binance:btc", RedisTtl.PREMIUM)
        }
        verify(exactly = 0) { hashOps.putAll("premium:btc", any()) }
    }

    private fun v2Payload(pair: MarketPair) = legacyPayload() + mapOf(
        "schema_version" to "2",
        "korea_exchange" to pair.koreaExchange.name,
        "foreign_exchange" to pair.foreignExchange.name,
        "fx_source" to "FX_PROVIDER",
        "fx_observed_at" to "1706499999000",
    )

    private fun legacyPayload() = mapOf(
        "symbol" to "BTC",
        "rate" to "1.50",
        "korea_price" to "129555000",
        "foreign_price" to "89277.10",
        "foreign_price_krw" to "127894943.46",
        "fx_rate" to "1432.60",
        "observed_at" to "1706500000000",
    )

    private fun snapshot(pair: MarketPair) = PremiumSnapshot(
        pair = pair,
        premiumRate = BigDecimal("1.50"),
        koreaPrice = BigDecimal("129555000"),
        foreignPrice = BigDecimal("89277.10"),
        foreignPriceInKrw = BigDecimal("127894943.46"),
        fxRate = BigDecimal("1432.60"),
        observedAt = Instant.ofEpochMilli(1_706_500_000_000L),
        fxObservedAt = Instant.ofEpochMilli(1_706_499_999_000L),
    )
}
