package io.premiumspread.infrastructure.batch.cache

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.premiumspread.domain.market.MarketPair
import io.premiumspread.domain.premium.PremiumAggregationSnapshot
import io.premiumspread.domain.premium.PremiumSnapshot
import io.premiumspread.domain.ticker.Exchange
import io.premiumspread.domain.ticker.Symbol
import io.premiumspread.infrastructure.common.cache.CacheReadMetrics
import io.premiumspread.infrastructure.common.cache.CacheReadOutcome
import io.premiumspread.infrastructure.common.cache.premium.PremiumAggregationCacheReader
import io.premiumspread.infrastructure.common.persistence.jdbc.premium.PremiumAggregation
import io.premiumspread.redis.AggregationTimeUnit
import io.premiumspread.redis.RedisKeyGenerator
import io.premiumspread.redis.RedisTtl
import io.premiumspread.redis.support.TimeSeriesCacheSupport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.data.redis.core.HashOperations
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ZSetOperations.TypedTuple
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit

class PremiumCacheOperationsBranchTest {
    private val from = Instant.parse("2026-07-15T00:00:00Z")
    private val to = from.plusSeconds(10)
    private val pair = MarketPair.default(Symbol("BTC"))
    private val customPair = MarketPair(Symbol("ETH"), Exchange.UPBIT, Exchange.BINANCE)

    @Test
    fun `seconds save writes pair aware key payload timestamp and ttl`() {
        val timeSeries = mockk<TimeSeriesCacheSupport>(relaxed = true)
        val operations = secondsOperations(timeSeries = timeSeries)
        val snapshot = premiumSnapshot(customPair, from)
        val key = RedisKeyGenerator.premiumV2SecondsKey("UPBIT", "BINANCE", "ETH")

        operations.saveToSeconds(snapshot)

        verify(exactly = 1) {
            timeSeries.add(key, "1.25:100:90:1400", from, RedisTtl.SECONDS_DATA)
        }
    }

    @Test
    fun `seconds symbol overload maps valid v2 payload and records hit`() {
        val timeSeries = mockk<TimeSeriesCacheSupport>()
        val metrics = mockk<CacheReadMetrics>(relaxed = true)
        val entry = tuple("1.25:100:90:1400", from)
        val key = RedisKeyGenerator.premiumV2SecondsKey("BITHUMB", "BINANCE", "BTC")
        every { timeSeries.rangeByTime(key, from, to) } returns listOf(entry)
        every { timeSeries.extractTimestamp(entry) } returns from

        val result = secondsOperations(timeSeries = timeSeries, metrics = metrics)
            .getSecondsData("BTC", from, to)

        assertThat(result).containsExactly(from to BigDecimal("1.25"))
        verify(exactly = 1) { metrics.record("premium_seconds", CacheReadOutcome.HIT) }
    }

    @Test
    fun `seconds empty v2 falls back to legacy shortens ttl and records outcomes`() {
        val redis = mockk<StringRedisTemplate>(relaxed = true)
        val timeSeries = mockk<TimeSeriesCacheSupport>()
        val metrics = mockk<CacheReadMetrics>(relaxed = true)
        val legacyEntry = tuple("2.5:101:91:1410", from)
        val v2Key = RedisKeyGenerator.premiumV2SecondsKey("BITHUMB", "BINANCE", "BTC")
        val legacyKey = RedisKeyGenerator.premiumSecondsKey("BTC")
        every { timeSeries.rangeByTime(v2Key, from, to) } returns emptyList()
        every { timeSeries.rangeByTime(legacyKey, from, to) } returns listOf(legacyEntry)
        every { timeSeries.extractTimestamp(legacyEntry) } returns from
        every { redis.getExpire(legacyKey, TimeUnit.MILLISECONDS) } returns Long.MAX_VALUE

        val result = secondsOperations(redis, timeSeries, metrics)
            .getSecondsDataFull(pair, from, to)

        assertThat(result).containsExactly(PremiumCacheService.SecondsEntry(from, BigDecimal("2.5"), BigDecimal("1410")))
        verify { redis.expire(legacyKey, RedisTtl.PREMIUM_LEGACY_READ_WINDOW) }
        verify { metrics.record("premium_seconds", CacheReadOutcome.MISS) }
        verify { metrics.record("premium_seconds", CacheReadOutcome.LEGACY_HIT) }
    }

    @Test
    fun `seconds non default pair never reads symbol only legacy key`() {
        val timeSeries = mockk<TimeSeriesCacheSupport>()
        val metrics = mockk<CacheReadMetrics>(relaxed = true)
        val v2Key = RedisKeyGenerator.premiumV2SecondsKey("UPBIT", "BINANCE", "ETH")
        every { timeSeries.rangeByTime(v2Key, from, to) } returns emptyList()

        val result = secondsOperations(timeSeries = timeSeries, metrics = metrics)
            .getSecondsDataFull(customPair, from, to)

        assertThat(result).isEmpty()
        verify(exactly = 1) { timeSeries.rangeByTime(v2Key, from, to) }
        verify { metrics.record("premium_seconds", CacheReadOutcome.MISS) }
    }

    @Test
    fun `seconds corrupt v2 payload is atomic and never records hit`() {
        val timeSeries = mockk<TimeSeriesCacheSupport>()
        val metrics = mockk<CacheReadMetrics>(relaxed = true)
        val valid = tuple("1.25:100:90:1400", from)
        val corrupt = tuple("2.0:broken", from.plusSeconds(1))
        every { timeSeries.rangeByTime(any(), from, to) } returns listOf(valid, corrupt)
        every { timeSeries.extractTimestamp(valid) } returns from

        val result = secondsOperations(timeSeries = timeSeries, metrics = metrics)
            .getSecondsDataFull("BTC", from, to)

        assertThat(result).isEmpty()
        verify(exactly = 1) { metrics.record("premium_seconds", CacheReadOutcome.CORRUPT) }
        verify(exactly = 0) { metrics.record("premium_seconds", CacheReadOutcome.HIT) }
    }

    @Test
    fun `seconds redis failure records error without legacy fallback`() {
        val timeSeries = mockk<TimeSeriesCacheSupport>()
        val metrics = mockk<CacheReadMetrics>(relaxed = true)
        every { timeSeries.rangeByTime(any(), from, to) } throws DataAccessResourceFailureException("redis down")

        val result = secondsOperations(timeSeries = timeSeries, metrics = metrics)
            .getSecondsDataFull(pair, from, to)

        assertThat(result).isEmpty()
        verify(exactly = 1) { metrics.record("premium_seconds", CacheReadOutcome.ERROR) }
        verify(exactly = 0) { metrics.record("premium_seconds", CacheReadOutcome.MISS) }
    }

    @Test
    fun `aggregation save uses pair key serialized values and interval ttl`() {
        val timeSeries = mockk<TimeSeriesCacheSupport>(relaxed = true)
        val operations = aggregationOperations(timeSeries = timeSeries)
        val withFx = aggregation(avg = "2", count = 3, fxRate = "1400")
        val withoutFx = aggregation(avg = "3", count = 1, fxRate = null)

        operations.saveAggregation(AggregationTimeUnit.HOURS, customPair, from, withFx)
        operations.saveAggregation(AggregationTimeUnit.MINUTES, "BTC", to, withoutFx)

        verify {
            timeSeries.add(
                RedisKeyGenerator.premiumV2AggregationKey("UPBIT", "BINANCE", "ETH", "HOURS"),
                "2:2:2:2:2:3:1400",
                from,
                RedisTtl.HOURS_DATA,
            )
        }
        verify {
            timeSeries.add(
                RedisKeyGenerator.premiumV2AggregationKey("BITHUMB", "BINANCE", "BTC", "MINUTES"),
                "3:3:3:3:3:1:",
                to,
                RedisTtl.MINUTES_DATA,
            )
        }
    }

    @Test
    fun `aggregation reads every persisted interval and maps snapshots`() {
        val reader = mockk<PremiumAggregationCacheReader>()
        val operations = aggregationOperations(reader = reader)
        val minute = snapshot(pair, from, "1", 2, "1300")
        val hour = snapshot(pair, from.plusSeconds(1), "2", 3, "1400")
        val day = snapshot(pair, from.plusSeconds(2), "3", 4, null)
        every { reader.findByInterval(pair, "1m", from, to) } returns listOf(minute)
        every { reader.findByInterval(pair, "1h", from, to) } returns listOf(hour)
        every { reader.findByInterval(pair, "1d", from, to) } returns listOf(day)

        val minutes = operations.getAggregationData(AggregationTimeUnit.MINUTES, "BTC", from, to)
        val hours = operations.getAggregationData(AggregationTimeUnit.HOURS, pair, from, to)
        val days = operations.getAggregationData(AggregationTimeUnit.DAYS, pair, from, to)
        val seconds = operations.getAggregationData(AggregationTimeUnit.SECONDS, pair, from, to)

        assertThat(minutes.single().second.avg).isEqualByComparingTo("1")
        assertThat(hours.single().second.fxRate).isEqualByComparingTo("1400")
        assertThat(days.single().second.count).isEqualTo(4)
        assertThat(seconds).isEmpty()
    }

    @Test
    fun `seconds aggregation calculates ohlc average count and latest fx`() {
        val seconds = mockk<PremiumSecondsCacheOperations>()
        val operations = aggregationOperations(seconds = seconds)
        every { seconds.getSecondsDataFull(pair, from, to) } returns listOf(
            PremiumCacheService.SecondsEntry(from, BigDecimal("3"), BigDecimal("1300")),
            PremiumCacheService.SecondsEntry(from.plusSeconds(1), BigDecimal("1"), BigDecimal("1400")),
            PremiumCacheService.SecondsEntry(from.plusSeconds(2), BigDecimal("2"), BigDecimal("1500")),
        )

        val result = operations.aggregateSecondsData("BTC", from, to)

        assertThat(result?.high).isEqualByComparingTo("3")
        assertThat(result?.low).isEqualByComparingTo("1")
        assertThat(result?.open).isEqualByComparingTo("3")
        assertThat(result?.close).isEqualByComparingTo("2")
        assertThat(result?.avg).isEqualByComparingTo("2.0000")
        assertThat(result?.count).isEqualTo(3)
        assertThat(result?.fxRate).isEqualByComparingTo("1500")
    }

    @Test
    fun `aggregation utilities return null for empty sources and weighted result otherwise`() {
        val reader = mockk<PremiumAggregationCacheReader>()
        val seconds = mockk<PremiumSecondsCacheOperations>()
        val operations = aggregationOperations(reader = reader, seconds = seconds)
        every { seconds.getSecondsDataFull(pair, from, to) } returns emptyList()
        every { reader.findByInterval(pair, "1m", from, to) } returns listOf(
            snapshot(pair, from, "1", 2, "1300"),
            snapshot(pair, from.plusSeconds(1), "4", 1, "1400"),
        ) andThen emptyList()

        assertThat(operations.aggregateSecondsData(pair, from, to)).isNull()
        val result = operations.aggregateData(AggregationTimeUnit.MINUTES, "BTC", from, to)
        assertThat(result?.avg).isEqualByComparingTo("2.0000")
        assertThat(result?.count).isEqualTo(3)
        assertThat(result?.fxRate).isEqualByComparingTo("1400")
        assertThat(operations.aggregateData(AggregationTimeUnit.MINUTES, pair, from, to)).isNull()
    }

    @Test
    fun `summary save serializes hash and applies interval specific ttl`() {
        val redis = mockk<StringRedisTemplate>(relaxed = true)
        val hash = mockk<HashOperations<String, String, String>>(relaxed = true)
        every { redis.opsForHash<String, String>() } returns hash
        val operations = summaryOperations(redis = redis)
        val summary = summary()

        listOf(
            "1m" to RedisTtl.Summary.ONE_MINUTE,
            "10m" to RedisTtl.Summary.TEN_MINUTES,
            "1h" to RedisTtl.Summary.ONE_HOUR,
            "1d" to RedisTtl.Summary.ONE_DAY,
            "unknown" to RedisTtl.Summary.ONE_MINUTE,
        ).forEach { (interval, ttl) ->
            operations.saveSummary(interval, "BTC", summary)
            val key = RedisKeyGenerator.premiumV2SummaryKey("BITHUMB", "BINANCE", "BTC", interval)
            verify { redis.expire(key, ttl) }
        }
        verify {
            hash.putAll(
                RedisKeyGenerator.premiumV2SummaryKey("BITHUMB", "BINANCE", "BTC", "1m"),
                mapOf(
                    "high" to "3",
                    "low" to "1",
                    "current" to "2",
                    "current_ts" to from.toEpochMilli().toString(),
                    "updated_at" to to.toEpochMilli().toString(),
                ),
            )
        }
    }

    @Test
    fun `summary empty v2 falls back to legacy shortens ttl and records outcomes`() {
        val redis = mockk<StringRedisTemplate>(relaxed = true)
        val hashOperations = mockk<HashOperations<String, String, String>>()
        val metrics = mockk<CacheReadMetrics>(relaxed = true)
        val v2Key = RedisKeyGenerator.premiumV2SummaryKey("BITHUMB", "BINANCE", "BTC", "1m")
        val legacyKey = RedisKeyGenerator.summaryKey("1m", "BTC")
        every { redis.opsForHash<String, String>() } returns hashOperations
        every { hashOperations.entries(v2Key) } returns emptyMap()
        every { hashOperations.entries(legacyKey) } returns summaryHash()
        every { redis.getExpire(legacyKey, TimeUnit.MILLISECONDS) } returns -1L

        val result = summaryOperations(redis = redis, metrics = metrics).getSummary("1m", "BTC")

        assertThat(result).isEqualTo(summary())
        verify { redis.expire(legacyKey, RedisTtl.PREMIUM_LEGACY_READ_WINDOW) }
        verify { metrics.record("premium_summary", CacheReadOutcome.MISS) }
        verify { metrics.record("premium_summary", CacheReadOutcome.LEGACY_HIT) }
    }

    @Test
    fun `summary non default pair does not consume legacy symbol cache`() {
        val redis = mockk<StringRedisTemplate>()
        val hashOperations = mockk<HashOperations<String, String, String>>()
        val metrics = mockk<CacheReadMetrics>(relaxed = true)
        every { redis.opsForHash<String, String>() } returns hashOperations
        every { hashOperations.entries(any()) } returns emptyMap()

        val result = summaryOperations(redis = redis, metrics = metrics).getSummary("1h", customPair)

        assertThat(result).isNull()
        verify(exactly = 1) { hashOperations.entries(any()) }
        verify { metrics.record("premium_summary", CacheReadOutcome.MISS) }
    }

    @Test
    fun `summary corrupt payload is rejected atomically without hit`() {
        val redis = mockk<StringRedisTemplate>()
        val hashOperations = mockk<HashOperations<String, String, String>>()
        val metrics = mockk<CacheReadMetrics>(relaxed = true)
        every { redis.opsForHash<String, String>() } returns hashOperations
        every { hashOperations.entries(any()) } returns summaryHash() + ("current_ts" to "not-a-timestamp")

        val result = summaryOperations(redis = redis, metrics = metrics).getSummary("1m", pair)

        assertThat(result).isNull()
        verify { metrics.record("premium_summary", CacheReadOutcome.CORRUPT) }
        verify(exactly = 0) { metrics.record("premium_summary", CacheReadOutcome.HIT) }
    }

    @Test
    fun `summary redis failure records error and stops fallback`() {
        val redis = mockk<StringRedisTemplate>()
        val hashOperations = mockk<HashOperations<String, String, String>>()
        val metrics = mockk<CacheReadMetrics>(relaxed = true)
        every { redis.opsForHash<String, String>() } returns hashOperations
        every { hashOperations.entries(any()) } throws DataAccessResourceFailureException("redis down")

        val result = summaryOperations(redis = redis, metrics = metrics).getSummary("1d", pair)

        assertThat(result).isNull()
        verify(exactly = 1) { metrics.record("premium_summary", CacheReadOutcome.ERROR) }
        verify(exactly = 0) { metrics.record("premium_summary", CacheReadOutcome.MISS) }
    }

    @Test
    fun `summary calculations use extrema latest value and fixed update time`() {
        val seconds = mockk<PremiumSecondsCacheOperations>()
        val aggregations = mockk<PremiumAggregationCacheOperations>()
        val operations = summaryOperations(seconds = seconds, aggregations = aggregations)
        every { seconds.getSecondsData(pair, from, to) } returns listOf(
            from to BigDecimal("3"),
            from.plusSeconds(1) to BigDecimal("1"),
            from.plusSeconds(2) to BigDecimal("2"),
        ) andThen emptyList()
        every { aggregations.getAggregationData(AggregationTimeUnit.HOURS, pair, from, to) } returns listOf(
            from to aggregation("2", 1, "1300").copy(high = BigDecimal("4"), low = BigDecimal("2")),
            from.plusSeconds(3) to aggregation("3", 1, "1400").copy(high = BigDecimal("5"), low = BigDecimal("1")),
        ) andThen emptyList()

        val secondsSummary = operations.calculateSummaryFromSeconds("BTC", from, to)
        assertThat(secondsSummary).isEqualTo(
            PremiumCacheService.PremiumSummary(BigDecimal("3"), BigDecimal("1"), BigDecimal("2"), from.plusSeconds(2), to),
        )
        assertThat(operations.calculateSummaryFromSeconds(pair, from, to)).isNull()

        val aggregationSummary = operations.calculateSummary(AggregationTimeUnit.HOURS, "BTC", from, to)
        assertThat(aggregationSummary).isEqualTo(
            PremiumCacheService.PremiumSummary(BigDecimal("5"), BigDecimal("1"), BigDecimal("3"), from.plusSeconds(3), to),
        )
        assertThat(operations.calculateSummary(AggregationTimeUnit.HOURS, pair, from, to)).isNull()
    }

    private fun secondsOperations(
        redis: StringRedisTemplate = mockk(relaxed = true),
        timeSeries: TimeSeriesCacheSupport = mockk(relaxed = true),
        metrics: CacheReadMetrics = mockk(relaxed = true),
    ) = PremiumSecondsCacheOperationsImpl(redis, timeSeries, metrics)

    private fun aggregationOperations(
        timeSeries: TimeSeriesCacheSupport = mockk(relaxed = true),
        reader: PremiumAggregationCacheReader = mockk(relaxed = true),
        seconds: PremiumSecondsCacheOperations = mockk(relaxed = true),
    ) = PremiumAggregationCacheOperationsImpl(timeSeries, reader, seconds)

    private fun summaryOperations(
        redis: StringRedisTemplate = mockk(relaxed = true),
        seconds: PremiumSecondsCacheOperations = mockk(relaxed = true),
        aggregations: PremiumAggregationCacheOperations = mockk(relaxed = true),
        metrics: CacheReadMetrics = mockk(relaxed = true),
    ) = PremiumSummaryCacheOperationsImpl(redis, Clock.fixed(to, ZoneOffset.UTC), seconds, aggregations, metrics)

    private fun premiumSnapshot(pair: MarketPair, observedAt: Instant) = PremiumSnapshot(
        pair = pair,
        premiumRate = BigDecimal("1.25"),
        koreaPrice = BigDecimal("100"),
        foreignPrice = BigDecimal("90"),
        foreignPriceInKrw = BigDecimal("126000"),
        fxRate = BigDecimal("1400"),
        observedAt = observedAt,
    )

    private fun aggregation(avg: String, count: Int, fxRate: String?) = PremiumAggregation(
        symbol = "BTC",
        high = BigDecimal(avg),
        low = BigDecimal(avg),
        open = BigDecimal(avg),
        close = BigDecimal(avg),
        avg = BigDecimal(avg),
        count = count,
        fxRate = fxRate?.let(::BigDecimal),
    )

    private fun snapshot(
        pair: MarketPair,
        observedAt: Instant,
        avg: String,
        count: Int,
        fxRate: String?,
    ) = PremiumAggregationSnapshot(
        pair = pair,
        high = BigDecimal(avg),
        low = BigDecimal(avg),
        open = BigDecimal(avg),
        close = BigDecimal(avg),
        avg = BigDecimal(avg),
        count = count,
        fxRate = fxRate?.let(::BigDecimal),
        observedAt = observedAt,
    )

    private fun summary() = PremiumCacheService.PremiumSummary(
        high = BigDecimal("3"),
        low = BigDecimal("1"),
        current = BigDecimal("2"),
        currentTimestamp = from,
        updatedAt = to,
    )

    private fun summaryHash() = mapOf(
        "high" to "3",
        "low" to "1",
        "current" to "2",
        "current_ts" to from.toEpochMilli().toString(),
        "updated_at" to to.toEpochMilli().toString(),
    )

    private fun tuple(value: String, timestamp: Instant): TypedTuple<String> = mockk<TypedTuple<String>>().also { tuple ->
        every { tuple.value } returns value
        every { tuple.score } returns timestamp.toEpochMilli().toDouble()
    }
}
