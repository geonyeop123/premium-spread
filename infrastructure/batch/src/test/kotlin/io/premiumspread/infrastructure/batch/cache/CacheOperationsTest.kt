package io.premiumspread.infrastructure.batch.cache

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.premiumspread.domain.market.MarketPair
import io.premiumspread.domain.ticker.Symbol
import io.premiumspread.infrastructure.common.cache.CacheReadMetrics
import io.premiumspread.infrastructure.common.cache.CacheReadOutcome
import io.premiumspread.redis.support.TimeSeriesCacheSupport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.HashOperations
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ZSetOperations.TypedTuple
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class CacheOperationsTest {
    private val from = Instant.parse("2026-07-15T00:00:00Z")
    private val to = from.plusSeconds(10)

    @Test
    fun `ticker seconds parser returns no partial data when one entry is corrupt`() {
        val valid = tuple("1000:123.45", from)
        val corrupt = tuple("1001:not-a-number", from.plusSeconds(1))

        val result = parseTickerSeconds(listOf(valid, corrupt)) { entry ->
            entry.score?.toLong()?.let(Instant::ofEpochMilli)
        }

        assertThat(result).isEqualTo(TickerSecondsParseResult.Corrupt)
    }

    @Test
    fun `premium seconds parser records a hit only after the complete payload is parsed`() {
        val redisTemplate = mockk<StringRedisTemplate>(relaxed = true)
        val timeSeriesCache = mockk<TimeSeriesCacheSupport>()
        val metrics = mockk<CacheReadMetrics>(relaxed = true)
        val operations = PremiumSecondsCacheOperationsImpl(redisTemplate, timeSeriesCache, metrics)
        val pair = MarketPair.default(Symbol("BTC"))
        val entry = tuple("1.25:100:90:1400", from)
        every { timeSeriesCache.rangeByTime(any(), from, to) } returns listOf(entry)
        every { timeSeriesCache.extractTimestamp(entry) } returns from

        val result = operations.getSecondsDataFull(pair, from, to)

        assertThat(result).containsExactly(PremiumCacheService.SecondsEntry(from, BigDecimal("1.25"), BigDecimal("1400")))
        verify(exactly = 1) { metrics.record("premium_seconds", CacheReadOutcome.HIT) }
    }

    @Test
    fun `summary cache parses all required fields before recording a hit`() {
        val redisTemplate = mockk<StringRedisTemplate>()
        val hashOperations = mockk<HashOperations<String, String, String>>()
        val metrics = mockk<CacheReadMetrics>(relaxed = true)
        val pair = MarketPair.default(Symbol("BTC"))
        every { redisTemplate.opsForHash<String, String>() } returns hashOperations
        every { hashOperations.entries(any()) } returns mapOf(
            "high" to "3.0",
            "low" to "1.0",
            "current" to "2.0",
            "current_ts" to from.toEpochMilli().toString(),
            "updated_at" to to.toEpochMilli().toString(),
        )
        val operations = PremiumSummaryCacheOperationsImpl(
            redisTemplate = redisTemplate,
            clock = Clock.fixed(to, ZoneOffset.UTC),
            secondsCache = mockk(),
            aggregationCache = mockk(),
            metrics = metrics,
        )

        val result = operations.getSummary("1m", pair)

        assertThat(result?.current).isEqualByComparingTo("2.0")
        verify(exactly = 1) { metrics.record("premium_summary", CacheReadOutcome.HIT) }
    }

    private fun tuple(value: String, timestamp: Instant): TypedTuple<String> = mockk<TypedTuple<String>>().also { tuple ->
        every { tuple.value } returns value
        every { tuple.score } returns timestamp.toEpochMilli().toDouble()
    }
}
