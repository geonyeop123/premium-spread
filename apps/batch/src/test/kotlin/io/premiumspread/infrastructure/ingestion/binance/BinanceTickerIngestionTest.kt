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
    private val fixedNow: Instant = Instant.parse("2026-05-12T00:00:10Z")
    private lateinit var cache: TickerCacheService
    private lateinit var metrics: WebSocketMetrics
    private lateinit var alertService: AlertService
    private lateinit var ingestion: BinanceTickerIngestion

    @BeforeEach
    fun setUp() {
        cache = mockk(relaxed = true)
        metrics = mockk(relaxed = true)
        alertService = mockk(relaxed = true)
        ingestion = BinanceTickerIngestion(
            tickerCacheService = cache,
            metrics = metrics,
            alertService = alertService,
            clock = Clock.fixed(fixedNow, ZoneOffset.UTC),
        )
    }

    @Test
    fun `정상 메시지는 hash를 저장하고 latest를 갱신하며 lag를 기록한다`() {
        val ts = Instant.parse("2026-05-12T00:00:08Z") // now - 2s
        val ticker = tickerAt(ts)

        ingestion.onMessage(ticker)

        verify(exactly = 1) { cache.save(ticker) }
        verify(exactly = 1) { metrics.recordLag("binance", 2000L) }
        assertThat(ingestion.latest()?.ticker).isEqualTo(ticker)
        assertThat(ingestion.latest()?.receivedAt).isEqualTo(fixedNow)
    }

    @Test
    fun `ZSet 직접 저장은 하지 않는다 (flush job이 담당)`() {
        ingestion.onMessage(tickerAt(Instant.parse("2026-05-12T00:00:08Z")))

        verify(exactly = 0) { cache.saveToSeconds(any()) }
        verify(exactly = 0) { cache.saveToSecondsWithScore(any(), any()) }
    }

    @Test
    fun `직전보다 strict하게 오래된 timestamp는 폐기되고 out_of_order가 증가한다`() {
        val first = tickerAt(Instant.parse("2026-05-12T00:00:05Z"))
        val stale = tickerAt(Instant.parse("2026-05-12T00:00:01Z"))
        ingestion.onMessage(first)

        ingestion.onMessage(stale)

        verify(exactly = 0) { cache.save(stale) }
        verify(exactly = 1) { metrics.recordOutOfOrder("binance") }
        assertThat(ingestion.latest()?.ticker).isEqualTo(first)
    }

    @Test
    fun `같은 ms timestamp 메시지는 수용된다 (bookTicker는 동일 eventTime에 복수 push 정상)`() {
        val ts = Instant.parse("2026-05-12T00:00:05Z")
        val first = tickerAt(ts)
        val sameMs = tickerAt(ts).copy(price = BigDecimal("200.00"))

        ingestion.onMessage(first)
        ingestion.onMessage(sameMs)

        verify(exactly = 1) { cache.save(first) }
        verify(exactly = 1) { cache.save(sameMs) }
        verify(exactly = 0) { metrics.recordOutOfOrder("binance") }
        assertThat(ingestion.latest()?.ticker).isEqualTo(sameMs)
    }

    @Test
    fun `동시에 다수 스레드에서 호출해도 최종 latest는 최대 timestamp가 된다 (CAS atomic 보장)`() {
        val threadCount = 16
        val perThread = 100
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
                } finally {
                    latch.countDown()
                }
            }
        }
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue
        executor.shutdownNow()

        val final = ingestion.latest()
        assertThat(final).isNotNull
        assertThat(final!!.ticker.timestamp).isEqualTo(base.plusMillis(maxOffsetMs))
    }

    @Test
    fun `cache save 5회 연속 실패면 sendCriticalAlert가 호출되고 counter는 리셋된다`() {
        every { cache.save(any()) } throws RuntimeException("redis down")

        repeat(5) {
            ingestion.onMessage(tickerAt(Instant.parse("2026-05-12T00:00:00Z").plusMillis(it.toLong())))
        }

        verify(exactly = 5) { metrics.recordFlushError(eq("binance"), any()) }
        verify(exactly = 1) { alertService.sendCriticalAlert(match { it.contains("5회 연속") }) }
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
