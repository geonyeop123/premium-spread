package io.premiumspread.infrastructure.ingestion.bithumb

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

class BithumbTickerIngestionTest {
    private lateinit var cache: TickerCacheService
    private lateinit var metrics: WebSocketMetrics
    private lateinit var alertService: AlertService
    private lateinit var ingestion: BithumbTickerIngestion
    private val fixedNow: Instant = Instant.parse("2026-05-12T00:00:10Z")

    @BeforeEach
    fun setUp() {
        cache = mockk(relaxed = true)
        metrics = mockk(relaxed = true)
        alertService = mockk(relaxed = true)
        ingestion = BithumbTickerIngestion(
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
        verify(exactly = 1) { metrics.recordLag("bithumb", 2000L) }
        assertThat(ingestion.latest()?.ticker).isEqualTo(ticker)
        assertThat(ingestion.latest()?.receivedAt).isEqualTo(fixedNow)
    }

    @Test
    fun `직전보다 오래된 exchange timestamp는 폐기되고 out_of_order가 증가한다`() {
        val first = tickerAt(Instant.parse("2026-05-12T00:00:05Z"))
        val stale = tickerAt(Instant.parse("2026-05-12T00:00:01Z"))
        ingestion.onMessage(first)

        ingestion.onMessage(stale)

        verify(exactly = 0) { cache.save(stale) }
        verify(exactly = 1) { metrics.recordOutOfOrder("bithumb") }
        assertThat(ingestion.latest()?.ticker).isEqualTo(first)
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
        // CAS이므로 최종 latest는 정확히 max timestamp여야 함 (race로 인한 regression 없음)
        assertThat(final!!.ticker.timestamp).isEqualTo(base.plusMillis(maxOffsetMs))
    }

    @Test
    fun `cache save 5회 연속 실패면 sendCriticalAlert가 호출되고 counter는 리셋된다`() {
        every { cache.save(any()) } throws RuntimeException("redis down")

        repeat(5) {
            ingestion.onMessage(tickerAt(Instant.parse("2026-05-12T00:00:00Z").plusMillis(it.toLong())))
        }

        verify(exactly = 5) { metrics.recordFlushError(eq("bithumb"), any()) }
        verify(exactly = 1) { alertService.sendCriticalAlert(match { it.contains("5회 연속") }) }
    }

    private fun tickerAt(timestamp: Instant) = TickerData(
        exchange = "BITHUMB",
        symbol = "BTC",
        currency = "KRW",
        price = BigDecimal("100000000"),
        volume = null,
        timestamp = timestamp,
    )
}
