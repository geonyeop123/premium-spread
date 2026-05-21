package io.premiumspread.infrastructure.ingestion.binance

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.premiumspread.cache.TickerCacheService
import io.premiumspread.client.TickerData
import io.premiumspread.infrastructure.ingestion.binance.BinanceTickerIngestion.LatestTicker
import io.premiumspread.infrastructure.websocket.WebSocketMetrics
import io.premiumspread.monitoring.AlertService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class BinanceFlushJobTest {
    private val now = Instant.parse("2026-05-12T00:00:10Z")
    private lateinit var ingestion: BinanceTickerIngestion
    private lateinit var cache: TickerCacheService
    private lateinit var metrics: WebSocketMetrics
    private lateinit var alertService: AlertService
    private lateinit var redisTemplate: StringRedisTemplate
    private lateinit var valueOps: ValueOperations<String, String>
    private lateinit var job: BinanceFlushJob

    @BeforeEach
    fun setUp() {
        ingestion = mockk()
        cache = mockk(relaxed = true)
        metrics = mockk(relaxed = true)
        alertService = mockk(relaxed = true)
        redisTemplate = mockk()
        valueOps = mockk(relaxed = true)
        every { redisTemplate.opsForValue() } returns valueOps
        job = BinanceFlushJob(
            ingestion, cache, metrics, alertService, redisTemplate,
            clock = Clock.fixed(now, ZoneOffset.UTC),
        )
    }

    @Test
    fun `latest가 null이면 아무것도 하지 않는다`() {
        every { ingestion.latest() } returns null

        job.run()

        verify(exactly = 0) { cache.saveToSecondsWithScore(any(), any()) }
        verify(exactly = 0) { metrics.recordStale(any()) }
    }

    @Test
    fun `정상이면 ZSet score를 now로 저장하고 last-run을 갱신한다`() {
        val ticker = tickerData()
        every { ingestion.latest() } returns LatestTicker(ticker, now.minusSeconds(2))

        job.run()

        verify(exactly = 1) { cache.saveToSecondsWithScore(ticker, now) }
        verify(exactly = 1) { valueOps.set(BinanceFlushJob.LAST_RUN_KEY, now.toEpochMilli().toString(), any<Duration>()) }
        verify(exactly = 1) { metrics.recordFlush("binance") }
        verify(exactly = 0) { metrics.recordStale(any()) }
    }

    @Test
    fun `age가 10초를 초과하면 stale로 분류되고 flush를 호출하지 않는다`() {
        val ticker = tickerData()
        every { ingestion.latest() } returns LatestTicker(ticker, now.minusSeconds(11))

        job.run()

        verify(exactly = 0) { cache.saveToSecondsWithScore(any(), any()) }
        verify(exactly = 1) { metrics.recordStale("binance") }
    }

    @Test
    fun `save 예외 5회 연속이면 sendCriticalAlert를 호출하고 counter는 리셋된다`() {
        val ticker = tickerData()
        every { ingestion.latest() } returns LatestTicker(ticker, now.minusSeconds(2))
        every { cache.saveToSecondsWithScore(any(), any()) } throws RuntimeException("redis down")

        repeat(5) { job.run() }

        verify(exactly = 1) { alertService.sendCriticalAlert(match { it.contains("5회 연속") }) }

        job.run()
        verify(exactly = 1) { alertService.sendCriticalAlert(any()) }
    }

    @Test
    fun `save 예외 후 다음에 성공하면 consecutive failure counter는 리셋된다`() {
        val ticker = tickerData()
        every { ingestion.latest() } returns LatestTicker(ticker, now.minusSeconds(2))
        every { cache.saveToSecondsWithScore(any(), any()) } throws RuntimeException("temp") andThen Unit andThen Unit

        repeat(2) { job.run() } // 1 fail, 1 success

        verify(exactly = 0) { alertService.sendCriticalAlert(any()) }

        every { cache.saveToSecondsWithScore(any(), any()) } throws RuntimeException("temp")
        repeat(5) { job.run() }
        verify(exactly = 1) { alertService.sendCriticalAlert(any()) }
    }

    private fun tickerData() = TickerData(
        exchange = "BINANCE",
        symbol = "BTC",
        currency = "USD",
        price = BigDecimal("89277.10"),
        volume = null,
        timestamp = now.minusSeconds(2),
    )
}
