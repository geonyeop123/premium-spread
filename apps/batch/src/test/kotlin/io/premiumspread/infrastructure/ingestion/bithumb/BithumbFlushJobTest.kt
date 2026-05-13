package io.premiumspread.infrastructure.ingestion.bithumb

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.premiumspread.cache.TickerCacheService
import io.premiumspread.client.TickerData
import io.premiumspread.infrastructure.ingestion.bithumb.BithumbTickerIngestion.LatestTicker
import io.premiumspread.infrastructure.websocket.WebSocketMetrics
import io.premiumspread.monitoring.AlertService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class BithumbFlushJobTest {
    private val now = Instant.parse("2026-05-12T00:00:10Z")
    private lateinit var ingestion: BithumbTickerIngestion
    private lateinit var cache: TickerCacheService
    private lateinit var metrics: WebSocketMetrics
    private lateinit var alertService: AlertService
    private lateinit var redisTemplate: StringRedisTemplate
    private lateinit var valueOps: ValueOperations<String, String>
    private lateinit var job: BithumbFlushJob

    @BeforeEach
    fun setUp() {
        ingestion = mockk()
        cache = mockk(relaxed = true)
        metrics = mockk(relaxed = true)
        alertService = mockk(relaxed = true)
        redisTemplate = mockk()
        valueOps = mockk(relaxed = true)
        every { redisTemplate.opsForValue() } returns valueOps
        job = BithumbFlushJob(
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
        verify(exactly = 1) { valueOps.set(BithumbFlushJob.LAST_RUN_KEY, now.toEpochMilli().toString(), any<Duration>()) }
        verify(exactly = 1) { metrics.recordFlush("bithumb") }
        verify(exactly = 0) { metrics.recordStale(any()) }
    }

    @Test
    fun `age가 10초를 초과하면 stale로 분류되고 flush를 호출하지 않는다`() {
        val ticker = tickerData()
        every { ingestion.latest() } returns LatestTicker(ticker, now.minusSeconds(11))

        job.run()

        verify(exactly = 0) { cache.saveToSecondsWithScore(any(), any()) }
        verify(exactly = 1) { metrics.recordStale("bithumb") }
    }

    @Test
    fun `save 예외 5회 연속이면 sendCriticalAlert를 호출하고 counter는 리셋된다`() {
        val ticker = tickerData()
        every { ingestion.latest() } returns LatestTicker(ticker, now.minusSeconds(2))
        every { cache.saveToSecondsWithScore(any(), any()) } throws RuntimeException("redis down")

        repeat(5) { job.run() }

        verify(exactly = 1) { alertService.sendCriticalAlert(match { it.contains("5회 연속") }) }

        // 6번째에는 이미 리셋됐으니 다시 1회로 시작 — 추가 alert는 아직 없음
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

        // 다시 5회 실패해야 alert (counter 리셋 확인)
        every { cache.saveToSecondsWithScore(any(), any()) } throws RuntimeException("temp")
        repeat(5) { job.run() }
        verify(exactly = 1) { alertService.sendCriticalAlert(any()) }
    }

    private fun tickerData() = TickerData(
        exchange = "BITHUMB",
        symbol = "BTC",
        currency = "KRW",
        price = BigDecimal("100000000"),
        volume = null,
        timestamp = now.minusSeconds(2),
    )
}
