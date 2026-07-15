package io.premiumspread.infrastructure.batch.ingestion.bithumb

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.premiumspread.domain.job.OperatorAlert
import io.premiumspread.domain.job.OperatorAlertMessage
import io.premiumspread.infrastructure.batch.cache.TickerCacheService
import io.premiumspread.infrastructure.batch.exchange.TickerData
import io.premiumspread.infrastructure.batch.websocket.WebSocketMetrics
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class BithumbTickerIngestionTest {
    private lateinit var cache: TickerCacheService
    private lateinit var registry: SimpleMeterRegistry
    private lateinit var metrics: WebSocketMetrics
    private lateinit var alerts: ConcurrentLinkedQueue<OperatorAlertMessage>
    private lateinit var ingestion: BithumbTickerIngestion
    private val fixedNow = Instant.parse("2026-05-12T00:00:10Z")

    @BeforeEach
    fun setUp() {
        cache = mockk(relaxed = true)
        registry = SimpleMeterRegistry()
        metrics = WebSocketMetrics(registry, Clock.fixed(fixedNow, ZoneOffset.UTC))
        alerts = ConcurrentLinkedQueue()
        ingestion = BithumbTickerIngestion(
            tickerCacheService = cache,
            metrics = metrics,
            operatorAlert = OperatorAlert(alerts::add),
            clock = Clock.fixed(fixedNow, ZoneOffset.UTC),
        )
    }

    @Test
    fun `accepted ticker updates cache latest value and exchange lag`() {
        val ticker = tickerAt(Instant.parse("2026-05-12T00:00:08Z"))

        ingestion.onMessage(ticker)

        verify(exactly = 1) { cache.save(ticker) }
        assertThat(ingestion.latest()?.ticker).isEqualTo(ticker)
        assertThat(ingestion.latest()?.receivedAt).isEqualTo(fixedNow)
        assertThat(registry.find("ws.message.lag.ms").tag("exchange", "bithumb").timer()?.count())
            .isEqualTo(1L)
    }

    @Test
    fun `strictly older exchange timestamp is discarded as out of order`() {
        val newer = tickerAt(Instant.parse("2026-05-12T00:00:05Z"))
        val older = tickerAt(Instant.parse("2026-05-12T00:00:01Z")).copy(price = BigDecimal("99000000"))
        ingestion.onMessage(newer)

        ingestion.onMessage(older)

        verify(exactly = 0) { cache.save(older) }
        assertThat(ingestion.latest()?.ticker).isEqualTo(newer)
        assertThat(registry.find("ws.out_of_order").tag("exchange", "bithumb").counter()?.count())
            .isEqualTo(1.0)
    }

    @Test
    fun `same second timestamp remains acceptable for Bithumb precision`() {
        val timestamp = Instant.parse("2026-05-12T00:00:05Z")
        val first = tickerAt(timestamp)
        val sameSecond = tickerAt(timestamp).copy(price = BigDecimal("200000000"))

        ingestion.onMessage(first)
        ingestion.onMessage(sameSecond)

        verify(exactly = 1) { cache.save(first) }
        verify(exactly = 1) { cache.save(sameSecond) }
        assertThat(ingestion.latest()?.ticker).isEqualTo(sameSecond)
        assertThat(registry.find("ws.out_of_order").tag("exchange", "bithumb").counter()?.count() ?: 0.0)
            .isZero()
    }

    @Test
    fun `concurrent delivery retains the maximum exchange timestamp`() {
        val threadCount = 8
        val messagesPerThread = 50
        val executor = Executors.newFixedThreadPool(threadCount)
        val completed = CountDownLatch(threadCount)
        val base = Instant.parse("2026-05-12T00:00:00Z")

        repeat(threadCount) { thread ->
            executor.submit {
                try {
                    repeat(messagesPerThread) { index ->
                        ingestion.onMessage(tickerAt(base.plusMillis((thread * messagesPerThread + index).toLong())))
                    }
                } finally {
                    completed.countDown()
                }
            }
        }

        assertThat(completed.await(5, TimeUnit.SECONDS)).isTrue()
        executor.shutdownNow()
        assertThat(ingestion.latest()?.ticker?.timestamp)
            .isEqualTo(base.plusMillis((threadCount * messagesPerThread - 1).toLong()))
    }

    @Test
    fun `five consecutive cache failures emit alert and reset failure threshold`() {
        every { cache.save(any()) } throws RuntimeException("redis down")

        repeat(10) { index ->
            ingestion.onMessage(tickerAt(Instant.parse("2026-05-12T00:00:00Z").plusMillis(index.toLong())))
        }

        assertThat(alerts).hasSize(2)
        assertThat(alerts).allSatisfy { alert ->
            assertThat(alert.code).isEqualTo("websocket.ingestion.failure")
            assertThat(alert.message).contains("5회 연속")
        }
        assertThat(
            registry.find("ticker.flush").tag("exchange", "bithumb").tag("outcome", "failure")
            .tag("error", "other").counter()?.count(),
        ).isEqualTo(10.0)
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
