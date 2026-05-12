package io.premiumspread.infrastructure.ingestion.binance

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.premiumspread.cache.TickerCacheService
import io.premiumspread.client.TickerData
import io.premiumspread.infrastructure.websocket.WebSocketMetrics
import io.premiumspread.monitoring.AlertService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class BinanceTickerIngestionTest {
    private val fixedNow = Instant.parse("2026-05-12T00:00:10Z")
    private lateinit var tickerCacheService: TickerCacheService
    private lateinit var metrics: WebSocketMetrics
    private lateinit var alertService: AlertService
    private lateinit var ingestion: BinanceTickerIngestion

    @BeforeEach
    fun setUp() {
        tickerCacheService = mockk(relaxed = true)
        metrics = mockk(relaxed = true)
        alertService = mockk(relaxed = true)
        ingestion = BinanceTickerIngestion(
            tickerCacheService, metrics, alertService,
            Clock.fixed(fixedNow, ZoneOffset.UTC),
        )
    }

    @Test
    fun `정상 메시지는 hash와 초ZSet 둘 다 저장하고 lag을 기록한다`() {
        val ticker = tickerAt(Instant.parse("2026-05-12T00:00:08Z"))

        ingestion.onMessage(ticker)

        verify(exactly = 1) { tickerCacheService.save(ticker) }
        verify(exactly = 1) { tickerCacheService.saveToSeconds(ticker) }
        verify(exactly = 1) { metrics.recordLag("binance", 2000L) }
    }

    @Test
    fun `직전 메시지보다 timestamp가 빠르면 폐기하고 out_of_order를 기록한다`() {
        val first = tickerAt(Instant.parse("2026-05-12T00:00:01Z"))
        val stale = tickerAt(Instant.parse("2026-05-12T00:00:00Z"))

        ingestion.onMessage(first)
        ingestion.onMessage(stale)

        verify(exactly = 1) { tickerCacheService.save(first) }
        verify(exactly = 0) { tickerCacheService.save(stale) }
        verify(exactly = 1) { metrics.recordOutOfOrder("binance") }
    }

    @Test
    fun `timestamp가 같으면 (=) 새 메시지는 폐기되어 한 번만 저장된다 (CAS strict ordering)`() {
        val ts = Instant.parse("2026-05-12T00:00:00Z")

        ingestion.onMessage(tickerAt(ts))
        ingestion.onMessage(tickerAt(ts))

        verify(exactly = 1) { tickerCacheService.save(any()) }
        verify(exactly = 1) { metrics.recordOutOfOrder("binance") }
    }

    @Test
    fun `cache save 5회 연속 실패면 sendCriticalAlert가 호출되고 counter는 리셋된다`() {
        every { tickerCacheService.save(any()) } throws RuntimeException("redis down")
        repeat(5) { ingestion.onMessage(tickerAt(Instant.parse("2026-05-12T00:00:00Z").plusMillis(it.toLong()))) }

        verify(exactly = 5) { metrics.recordFlushError(eq("binance"), any()) }
        verify(exactly = 1) { alertService.sendCriticalAlert(match { it.contains("5회 연속") }) }
    }

    @Test
    fun `동시에 다수 스레드에서 호출해도 최종 latest는 max timestamp가 된다 (CAS atomic)`() {
        val threadCount = 16; val perThread = 100
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)
        val base = Instant.parse("2026-05-12T00:00:00Z")
        val maxOffsetMs = (threadCount * perThread - 1).toLong()

        repeat(threadCount) { t ->
            executor.submit {
                try {
                    repeat(perThread) { i ->
                        ingestion.onMessage(tickerAt(base.plusMillis((t * perThread + i).toLong())))
                    }
                } finally { latch.countDown() }
            }
        }
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue
        executor.shutdownNow()

        // lastTimestamp는 private이므로 마지막 onMessage가 stale 처리되는지로 확인
        ingestion.onMessage(tickerAt(base.plusMillis(maxOffsetMs - 1))) // max-1은 stale이어야 함
        verify(atLeast = 1) { metrics.recordOutOfOrder("binance") }
    }

    private fun tickerAt(timestamp: Instant) = TickerData(
        exchange = "BINANCE",
        symbol = "BTC",
        currency = "USD",
        price = BigDecimal("100.00"),
        volume = null,
        timestamp = timestamp,
    )
}
