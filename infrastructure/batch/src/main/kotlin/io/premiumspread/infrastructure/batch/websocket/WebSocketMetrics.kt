package io.premiumspread.infrastructure.batch.websocket

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.stereotype.Component
import org.springframework.dao.DataAccessException
import java.util.concurrent.ConcurrentHashMap
import java.time.Clock
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

@Component
class WebSocketMetrics(
    private val registry: MeterRegistry,
    private val clock: Clock,
) {
    private val connectionStates = ConcurrentHashMap<String, AtomicReference<Double>>()
    private val lastMessageMs = ConcurrentHashMap<String, AtomicLong>()

    fun setConnectionState(exchange: String, connected: Boolean) {
        val ref = connectionStates.computeIfAbsent(exchange) {
            val initial = AtomicReference(0.0)
            Gauge.builder("ws.connection.state", initial) { it.get() }
                .tag("exchange", exchange)
                .description("1=connected, 0=disconnected")
                .register(registry)
            initial
        }
        ref.set(if (connected) 1.0 else 0.0)
    }

    fun recordMessage(exchange: String) {
        Counter.builder("ws.message.received").tag("exchange", exchange).register(registry).increment()
    }

    fun recordReconnect(exchange: String) {
        Counter.builder("ws.reconnect.attempt").tag("exchange", exchange).register(registry).increment()
    }

    fun recordLag(exchange: String, lagMs: Long) {
        Timer.builder("ws.message.lag.ms").tag("exchange", exchange).register(registry)
            .record(lagMs, TimeUnit.MILLISECONDS)
    }

    fun recordFirstMessageTimeout(exchange: String) {
        Counter.builder("ws.first.message.timeout").tag("exchange", exchange).register(registry).increment()
    }

    fun recordOutOfOrder(exchange: String) {
        Counter.builder("ws.out_of_order").tag("exchange", exchange).register(registry).increment()
    }

    fun recordStale(exchange: String) {
        Counter.builder("ws.stale").tag("exchange", exchange).register(registry).increment()
    }

    fun recordFlush(exchange: String) {
        Counter.builder("ticker.flush")
            .tag("exchange", exchange)
            .tag("outcome", "success")
            .tag("error", "none")
            .register(registry).increment()
    }

    fun recordFlushError(exchange: String, exception: Exception) {
        Counter.builder("ticker.flush")
            .tag("exchange", exchange)
            .tag("outcome", "failure")
            .tag("error", if (exception is DataAccessException) "data_access" else "other")
            .register(registry).increment()
    }

    fun onMessageReceivedAt(exchange: String, epochMs: Long) {
        val ref = lastMessageMs.computeIfAbsent(exchange) {
            val initial = AtomicLong(epochMs)
            Gauge.builder("ws.last.message.age", initial) { (clock.millis() - it.get()) / 1000.0 }
                .tag("exchange", exchange)
                .description("Seconds since last received WebSocket message")
                .register(registry)
            initial
        }
        ref.set(epochMs)
    }

    fun currentEpochMilli(): Long = clock.millis()

    fun snapshot(exchange: String): WebSocketHealthSnapshot = WebSocketHealthSnapshot(
        connected = connectionStates[exchange]?.get() == 1.0,
        lastMessageAge = lastMessageMs[exchange]?.let { last ->
            java.time.Duration.ofMillis((clock.millis() - last.get()).coerceAtLeast(0L))
        },
    )
}

data class WebSocketHealthSnapshot(
    val connected: Boolean,
    val lastMessageAge: java.time.Duration?,
)
