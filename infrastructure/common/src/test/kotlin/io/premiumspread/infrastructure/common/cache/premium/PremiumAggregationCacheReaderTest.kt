package io.premiumspread.infrastructure.common.cache.premium

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.premiumspread.domain.market.MarketPair
import io.premiumspread.domain.ticker.Exchange
import io.premiumspread.domain.ticker.Symbol
import io.premiumspread.infrastructure.common.cache.CacheReadMetrics
import io.premiumspread.infrastructure.common.cache.CacheReadOutcome
import io.premiumspread.redis.RedisTtl
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ZSetOperations
import org.springframework.data.redis.RedisConnectionFailureException
import java.time.Instant
import java.time.Duration
import java.util.concurrent.TimeUnit

class PremiumAggregationCacheReaderTest {
    private val redisTemplate = mockk<StringRedisTemplate>()
    private val zSetOps = mockk<ZSetOperations<String, String>>()
    private val metrics = mockk<CacheReadMetrics>(relaxed = true)

    @Test
    fun `v2 aggregation은 requested non-default pair를 보존한다`() {
        val pair = MarketPair(Symbol("BTC"), Exchange.UPBIT, Exchange.BINANCE)
        val from = Instant.parse("2026-07-14T00:00:00Z")
        val to = from.plusSeconds(60)
        every { redisTemplate.opsForZSet() } returns zSetOps
        every {
            zSetOps.rangeByScoreWithScores(
                "premium:upbit:binance:btc:minutes",
                from.toEpochMilli().toDouble(),
                Math.nextDown(to.toEpochMilli().toDouble()),
            )
        } returns setOf(
            mockk<ZSetOperations.TypedTuple<String>>().also {
                every { it.value } returns "2.0:1.0:1.5:1.8:1.6:5:1430"
                every { it.score } returns from.toEpochMilli().toDouble()
            },
        )

        val result = PremiumAggregationCacheReader(redisTemplate, metrics)
            .findByInterval(pair, "1m", from, to)

        assertThat(result).hasSize(1)
        assertThat(result!![0].pair).isEqualTo(pair)
    }

    @Test
    fun `legacy aggregation hit은 cutover TTL로 축소한다`() {
        val pair = MarketPair.default(Symbol("BTC"))
        val from = Instant.parse("2026-07-14T00:00:00Z")
        val to = from.plusSeconds(60)
        val tuple = mockk<ZSetOperations.TypedTuple<String>>().also {
            every { it.value } returns "2.0:1.0:1.5:1.8:1.6:5:1430"
            every { it.score } returns from.toEpochMilli().toDouble()
        }
        every { redisTemplate.opsForZSet() } returns zSetOps
        every {
            zSetOps.rangeByScoreWithScores(
                "premium:bithumb:binance:btc:minutes",
                from.toEpochMilli().toDouble(),
                Math.nextDown(to.toEpochMilli().toDouble()),
            )
        } returns emptySet()
        every {
            zSetOps.rangeByScoreWithScores(
                "premium:minutes:btc",
                from.toEpochMilli().toDouble(),
                Math.nextDown(to.toEpochMilli().toDouble()),
            )
        } returns setOf(tuple)
        every { redisTemplate.expire(any(), any<Duration>()) } returns true
        every { redisTemplate.getExpire(any(), TimeUnit.MILLISECONDS) } returns -1L

        val result = PremiumAggregationCacheReader(redisTemplate, metrics)
            .findByInterval(pair, "1m", from, to)

        assertThat(result).hasSize(1)
        verify { redisTemplate.expire("premium:minutes:btc", RedisTtl.PREMIUM_LEGACY_READ_WINDOW) }
    }

    @Test
    fun `Redis 장애는 error로 기록하고 DB fallback을 위해 null을 반환한다`() {
        val pair = MarketPair.default(Symbol("BTC"))
        val from = Instant.parse("2026-07-14T00:00:00Z")
        every { redisTemplate.opsForZSet() } returns zSetOps
        every { zSetOps.rangeByScoreWithScores(any(), any(), any()) } throws RedisConnectionFailureException("down")

        val result = PremiumAggregationCacheReader(redisTemplate, metrics)
            .findByInterval(pair, "1m", from, from.plusSeconds(60))

        assertThat(result).isNull()
        verify { metrics.record("premium_aggregation", CacheReadOutcome.ERROR) }
    }

    @Test
    fun `non-default pair는 v2 miss여도 legacy aggregation을 조회하지 않는다`() {
        val pair = MarketPair(Symbol("BTC"), Exchange.UPBIT, Exchange.BINANCE)
        val from = Instant.parse("2026-07-14T00:00:00Z")
        every { redisTemplate.opsForZSet() } returns zSetOps
        every { zSetOps.rangeByScoreWithScores("premium:upbit:binance:btc:minutes", any(), any()) } returns emptySet()

        val result = PremiumAggregationCacheReader(redisTemplate, metrics)
            .findByInterval(pair, "1m", from, from.plusSeconds(60))

        assertThat(result).isNull()
        verify(exactly = 0) { zSetOps.rangeByScoreWithScores("premium:minutes:btc", any(), any()) }
    }

    @Test
    fun `aggregation row 하나라도 손상되면 부분 결과를 반환하지 않는다`() {
        val pair = MarketPair.default(Symbol("BTC"))
        val from = Instant.parse("2026-07-14T00:00:00Z")
        val valid = mockk<ZSetOperations.TypedTuple<String>>().also {
            every { it.value } returns "2.0:1.0:1.5:1.8:1.6:5:1430"
            every { it.score } returns from.toEpochMilli().toDouble()
        }
        val corrupt = mockk<ZSetOperations.TypedTuple<String>>().also {
            every { it.value } returns "corrupt"
            every { it.score } returns from.plusSeconds(1).toEpochMilli().toDouble()
        }
        every { redisTemplate.opsForZSet() } returns zSetOps
        every { zSetOps.rangeByScoreWithScores("premium:bithumb:binance:btc:minutes", any(), any()) } returns
            linkedSetOf(valid, corrupt)

        val result = PremiumAggregationCacheReader(redisTemplate, metrics)
            .findByInterval(pair, "1m", from, from.plusSeconds(60))

        assertThat(result).isNull()
        verify { metrics.record("premium_aggregation", CacheReadOutcome.CORRUPT) }
    }
}
