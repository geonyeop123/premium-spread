package io.premiumspread.infrastructure.batch.observability

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.premiumspread.infrastructure.batch.websocket.WebSocketMetrics
import io.premiumspread.infrastructure.batch.websocket.WebSocketStreamProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.actuate.health.Status
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class BatchIngestionReadinessHealthTest {
    private val now = Instant.parse("2026-07-15T00:00:00Z")
    private val metrics = WebSocketMetrics(SimpleMeterRegistry(), Clock.fixed(now, ZoneOffset.UTC))
    private val properties = WebSocketStreamProperties(
        connection = WebSocketStreamProperties.Connection(idleTimeout = Duration.ofSeconds(30)),
    )

    @Test
    fun `both mandatory streams must be connected and fresh`() {
        listOf("binance", "bithumb").forEach { exchange ->
            metrics.setConnectionState(exchange, true)
            metrics.onMessageReceivedAt(exchange, now.minusSeconds(5).toEpochMilli())
        }

        assertThat(BatchIngestionReadinessHealth(metrics, properties).health().status).isEqualTo(Status.UP)
    }

    @Test
    fun `stale mandatory stream makes batch unready`() {
        listOf("binance", "bithumb").forEach { exchange -> metrics.setConnectionState(exchange, true) }
        metrics.onMessageReceivedAt("binance", now.minusSeconds(31).toEpochMilli())
        metrics.onMessageReceivedAt("bithumb", now.minusSeconds(1).toEpochMilli())

        val health = BatchIngestionReadinessHealth(metrics, properties).health()
        assertThat(health.status).isEqualTo(Status.DOWN)
        assertThat(health.details["ingestion"]).isEqualTo("binance_stale")
    }
}
