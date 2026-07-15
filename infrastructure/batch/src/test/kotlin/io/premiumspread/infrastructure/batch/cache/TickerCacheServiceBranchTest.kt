package io.premiumspread.infrastructure.batch.cache

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.premiumspread.domain.market.MarketTick
import io.premiumspread.domain.ticker.Currency
import io.premiumspread.domain.ticker.Exchange
import io.premiumspread.domain.ticker.Quote
import io.premiumspread.domain.ticker.Symbol
import io.premiumspread.infrastructure.batch.exchange.TickerData
import io.premiumspread.infrastructure.common.cache.CacheReadMetrics
import io.premiumspread.infrastructure.common.cache.CacheReadOutcome
import io.premiumspread.infrastructure.common.cache.ticker.CachedTicker
import io.premiumspread.infrastructure.common.cache.ticker.TickerCacheReader
import io.premiumspread.infrastructure.common.cache.ticker.TickerCacheWriter
import io.premiumspread.infrastructure.common.persistence.jdbc.ticker.TickerAggregation
import io.premiumspread.redis.RedisTtl
import io.premiumspread.redis.TickerAggregationTimeUnit
import io.premiumspread.redis.support.TimeSeriesCacheSupport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ZSetOperations
import org.springframework.data.redis.core.ZSetOperations.TypedTuple
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class TickerCacheServiceBranchTest {
    private val now = Instant.parse("2026-07-15T00:05:00Z")
    private val from = now.minusSeconds(10)
    private val to = now

    @Test
    fun `current cache facade saves reads snapshots and handles misses`() {
        val reader = mockk<TickerCacheReader>()
        val writer = mockk<TickerCacheWriter>(relaxed = true)
        val service = service(reader = reader, writer = writer)
        val first = ticker("100", from)
        val second = ticker("110", to)
        val cached = CachedTicker("BITHUMB", "BTC", "KRW", BigDecimal("120"), BigDecimal("2"), now)
        every { reader.get("BITHUMB", "BTC") } returns cached andThen null andThen cached

        service.save(first)
        service.saveAll(first, second)
        val loaded = service.get("BITHUMB", "BTC")
        val missing = service.get("BITHUMB", "BTC")
        val snapshot = service.getSnapshot("BITHUMB", "BTC")

        verify(exactly = 2) { writer.save(first.toDomainSnapshot()) }
        verify(exactly = 1) { writer.save(second.toDomainSnapshot()) }
        assertThat(loaded?.price).isEqualByComparingTo("120")
        assertThat(missing).isNull()
        assertThat(snapshot?.price).isEqualByComparingTo("120")
    }

    @Test
    fun `market tick current and second writes preserve domain fields and sampled score`() {
        val redis = mockk<StringRedisTemplate>(relaxed = true)
        val zset = mockk<ZSetOperations<String, String>>(relaxed = true)
        val writer = mockk<TickerCacheWriter>(relaxed = true)
        every { redis.opsForZSet() } returns zset
        val service = service(redis = redis, writer = writer)
        val tick = MarketTick(
            exchange = Exchange.BITHUMB,
            quote = Quote.coin(Symbol("BTC"), Currency.KRW),
            price = BigDecimal("123.45"),
            observedAt = from,
        )

        service.saveCurrent(tick)
        service.saveSecond(tick, now)

        verify {
            writer.save(
                match { snapshot ->
                snapshot.exchange == "BITHUMB" &&
                    snapshot.symbol == "BTC" &&
                    snapshot.currency == "KRW" &&
                    snapshot.price.compareTo(BigDecimal("123.45")) == 0 &&
                    snapshot.observedAt == from
            },
            )
        }
        verify { zset.add("ticker:seconds:bithumb:btc", "${now.toEpochMilli()}:123.45", now.toEpochMilli().toDouble()) }
    }

    @Test
    fun `seconds save writes unique member ttl and retention cutoff`() {
        val redis = mockk<StringRedisTemplate>(relaxed = true)
        val zset = mockk<ZSetOperations<String, String>>(relaxed = true)
        every { redis.opsForZSet() } returns zset
        val service = service(redis = redis)
        val ticker = ticker("123.4500", from)
        val cutoff = now.minus(RedisTtl.SECONDS_DATA).toEpochMilli().toDouble()

        service.saveToSeconds(ticker)

        verify { zset.add("ticker:seconds:bithumb:btc", "${from.toEpochMilli()}:123.4500", from.toEpochMilli().toDouble()) }
        verify { redis.expire("ticker:seconds:bithumb:btc", RedisTtl.SECONDS_DATA) }
        verify {
            zset.removeRangeByScore("ticker:seconds:bithumb:btc", Double.NEGATIVE_INFINITY, cutoff)
        }
    }

    @Test
    fun `seconds read accepts new and legacy members and records one hit`() {
        val timeSeries = mockk<TimeSeriesCacheSupport>()
        val metrics = mockk<CacheReadMetrics>(relaxed = true)
        val newEntry = tuple("${from.toEpochMilli()}:123.45", from)
        val legacyEntry = tuple("125.00", to)
        every { timeSeries.rangeByTime("ticker:seconds:bithumb:btc", from, to) } returns listOf(newEntry, legacyEntry)
        every { timeSeries.extractTimestamp(newEntry) } returns from
        every { timeSeries.extractTimestamp(legacyEntry) } returns to

        val result = service(timeSeries = timeSeries, metrics = metrics)
            .getSecondsData("BITHUMB", "BTC", from, to)

        assertThat(result).containsExactly(from to BigDecimal("123.45"), to to BigDecimal("125.00"))
        verify(exactly = 1) { metrics.record("ticker_seconds", CacheReadOutcome.HIT) }
    }

    @Test
    fun `seconds miss corrupt and redis error report distinct metrics`() {
        val timeSeries = mockk<TimeSeriesCacheSupport>()
        val metrics = mockk<CacheReadMetrics>(relaxed = true)
        val corrupt = tuple("bad-price", from)
        every { timeSeries.rangeByTime(any(), from, to) } returns emptyList() andThen listOf(corrupt) andThenThrows
            DataAccessResourceFailureException("redis down")
        val service = service(timeSeries = timeSeries, metrics = metrics)

        assertThat(service.getSecondsData("BITHUMB", "BTC", from, to)).isEmpty()
        assertThat(service.getSecondsData("BITHUMB", "BTC", from, to)).isEmpty()
        assertThat(service.getSecondsData("BITHUMB", "BTC", from, to)).isEmpty()

        verify { metrics.record("ticker_seconds", CacheReadOutcome.MISS) }
        verify { metrics.record("ticker_seconds", CacheReadOutcome.CORRUPT) }
        verify { metrics.record("ticker_seconds", CacheReadOutcome.ERROR) }
        verify(exactly = 0) { metrics.record("ticker_seconds", CacheReadOutcome.HIT) }
    }

    @Test
    fun `ticker seconds parser rejects missing member and timestamp atomically`() {
        val missingMember = mockk<TypedTuple<String>>()
        val missingTimestamp = tuple("123", from)
        every { missingMember.value } returns null

        assertThat(parseTickerSeconds(listOf(missingMember)) { from })
            .isEqualTo(TickerSecondsParseResult.Corrupt)
        assertThat(parseTickerSeconds(listOf(missingTimestamp)) { null })
            .isEqualTo(TickerSecondsParseResult.Corrupt)
    }

    @Test
    fun `aggregation save serializes exact payload key timestamp and ttl`() {
        val timeSeries = mockk<TimeSeriesCacheSupport>(relaxed = true)
        val service = service(timeSeries = timeSeries)
        val aggregation = aggregation("2", 3)

        service.saveAggregation(TickerAggregationTimeUnit.HOURS, "BITHUMB", "BTC", now, aggregation)

        verify {
            timeSeries.add(
                "ticker:hours:bithumb:btc",
                "2:2:2:2:2:3",
                now,
                RedisTtl.HOURS_DATA,
            )
        }
    }

    @Test
    fun `aggregation read parses payload and records hit`() {
        val timeSeries = mockk<TimeSeriesCacheSupport>()
        val metrics = mockk<CacheReadMetrics>(relaxed = true)
        val entry = tuple("5:1:2:4:3:7", from)
        every { timeSeries.rangeByTime("ticker:minutes:bithumb:btc", from, to) } returns listOf(entry)
        every { timeSeries.extractTimestamp(entry) } returns from

        val result = service(timeSeries = timeSeries, metrics = metrics).getAggregationData(
            TickerAggregationTimeUnit.MINUTES,
            "BITHUMB",
            "BTC",
            "KRW",
            from,
            to,
        )

        assertThat(result.single().first).isEqualTo(from)
        assertThat(result.single().second).isEqualTo(
            TickerAggregation(
                "BITHUMB",
                "BTC",
                "KRW",
                BigDecimal("5"),
                BigDecimal("1"),
                BigDecimal("2"),
                BigDecimal("4"),
                BigDecimal("3"),
                7,
            ),
        )
        verify { metrics.record("ticker_aggregation", CacheReadOutcome.HIT) }
    }

    @Test
    fun `aggregation miss corrupt and redis error report distinct metrics`() {
        val timeSeries = mockk<TimeSeriesCacheSupport>()
        val metrics = mockk<CacheReadMetrics>(relaxed = true)
        val corrupt = tuple("5:1:broken:4:3:7", from)
        every { timeSeries.rangeByTime(any(), from, to) } returns emptyList() andThen listOf(corrupt) andThenThrows
            DataAccessResourceFailureException("redis down")
        every { timeSeries.extractTimestamp(corrupt) } returns from
        val service = service(timeSeries = timeSeries, metrics = metrics)

        repeat(3) {
            assertThat(
                service.getAggregationData(
                    TickerAggregationTimeUnit.MINUTES,
                    "BITHUMB",
                    "BTC",
                    "KRW",
                    from,
                    to,
                ),
            ).isEmpty()
        }

        verify { metrics.record("ticker_aggregation", CacheReadOutcome.MISS) }
        verify { metrics.record("ticker_aggregation", CacheReadOutcome.CORRUPT) }
        verify { metrics.record("ticker_aggregation", CacheReadOutcome.ERROR) }
        verify(exactly = 0) { metrics.record("ticker_aggregation", CacheReadOutcome.HIT) }
    }

    @Test
    fun `seconds aggregation returns ohlc average count and null for empty data`() {
        val timeSeries = mockk<TimeSeriesCacheSupport>()
        val entries = listOf(tuple("3", from), tuple("1", from.plusSeconds(1)), tuple("2", to))
        every { timeSeries.rangeByTime(any(), from, to) } returns entries andThen emptyList()
        entries.forEach { entry -> every { timeSeries.extractTimestamp(entry) } returns scoreInstant(entry) }
        val service = service(timeSeries = timeSeries)

        val result = service.aggregateSecondsData("BITHUMB", "BTC", "KRW", from, to)

        assertThat(result?.high).isEqualByComparingTo("3")
        assertThat(result?.low).isEqualByComparingTo("1")
        assertThat(result?.open).isEqualByComparingTo("3")
        assertThat(result?.close).isEqualByComparingTo("2")
        assertThat(result?.avg).isEqualByComparingTo("2.0000")
        assertThat(result?.count).isEqualTo(3)
        assertThat(service.aggregateSecondsData("BITHUMB", "BTC", "KRW", from, to)).isNull()
    }

    @Test
    fun `aggregation of aggregations uses weighted average and returns null for empty data`() {
        val timeSeries = mockk<TimeSeriesCacheSupport>()
        val first = tuple("5:1:2:4:1:2", from)
        val second = tuple("6:0:4:3:4:1", to)
        every { timeSeries.rangeByTime(any(), from, to) } returns listOf(first, second) andThen emptyList()
        every { timeSeries.extractTimestamp(first) } returns from
        every { timeSeries.extractTimestamp(second) } returns to
        val service = service(timeSeries = timeSeries)

        val result = service.aggregateData(TickerAggregationTimeUnit.HOURS, "BITHUMB", "BTC", "KRW", from, to)

        assertThat(result?.high).isEqualByComparingTo("6")
        assertThat(result?.low).isEqualByComparingTo("0")
        assertThat(result?.open).isEqualByComparingTo("2")
        assertThat(result?.close).isEqualByComparingTo("3")
        assertThat(result?.avg).isEqualByComparingTo("2.0000")
        assertThat(result?.count).isEqualTo(3)
        assertThat(service.aggregateData(TickerAggregationTimeUnit.HOURS, "BITHUMB", "BTC", "KRW", from, to)).isNull()
    }

    private fun service(
        redis: StringRedisTemplate = mockk(relaxed = true),
        timeSeries: TimeSeriesCacheSupport = mockk(relaxed = true),
        reader: TickerCacheReader = mockk(relaxed = true),
        writer: TickerCacheWriter = mockk(relaxed = true),
        metrics: CacheReadMetrics = mockk(relaxed = true),
    ) = TickerCacheService(redis, timeSeries, Clock.fixed(now, ZoneOffset.UTC), reader, writer, metrics)

    private fun ticker(price: String, timestamp: Instant) = TickerData(
        exchange = "BITHUMB",
        symbol = "BTC",
        currency = "KRW",
        price = BigDecimal(price),
        volume = BigDecimal("2"),
        timestamp = timestamp,
    )

    private fun aggregation(avg: String, count: Int) = TickerAggregation(
        exchange = "BITHUMB",
        symbol = "BTC",
        currency = "KRW",
        high = BigDecimal(avg),
        low = BigDecimal(avg),
        open = BigDecimal(avg),
        close = BigDecimal(avg),
        avg = BigDecimal(avg),
        count = count,
    )

    private fun tuple(value: String, timestamp: Instant): TypedTuple<String> = mockk<TypedTuple<String>>().also { tuple ->
        every { tuple.value } returns value
        every { tuple.score } returns timestamp.toEpochMilli().toDouble()
    }

    private fun scoreInstant(entry: TypedTuple<String>) = Instant.ofEpochMilli(entry.score!!.toLong())
}
