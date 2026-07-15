package io.premiumspread.infrastructure.batch.observability

import io.premiumspread.infrastructure.batch.websocket.WebSocketMetrics
import io.premiumspread.infrastructure.batch.websocket.WebSocketStreamProperties
import io.premiumspread.monitoring.CriticalIngestionHealth
import org.springframework.boot.actuate.health.Health

/** 필수 Binance/Bithumb stream이 연결되고 idle 임계값 이내 메시지를 받았을 때만 Batch를 ready로 본다. */
class BatchIngestionReadinessHealth(private val metrics: WebSocketMetrics, properties: WebSocketStreamProperties) :
    CriticalIngestionHealth {
    private val staleThreshold = properties.connection.idleTimeout

    override fun health(): Health {
        REQUIRED_EXCHANGES.forEach { exchange ->
            val snapshot = metrics.snapshot(exchange)
            if (!snapshot.connected) {
                return Health.down().withDetail("ingestion", "${exchange}_disconnected").build()
            }
            val age = snapshot.lastMessageAge
            if (age == null || age > staleThreshold) {
                return Health.down().withDetail("ingestion", "${exchange}_stale").build()
            }
        }
        return Health.up().withDetail("ingestion", "connected_and_fresh").build()
    }

    private companion object {
        val REQUIRED_EXCHANGES = listOf("binance", "bithumb")
    }
}
