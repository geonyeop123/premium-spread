package io.premiumspread.infrastructure.websocket

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.time.Clock
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WebSocketMetricsTest {

    private val registry = SimpleMeterRegistry()
    private val metrics = WebSocketMetrics(registry, Clock.systemUTC())

    @Test
    fun `setConnectionState 1로 설정하면 Gauge가 1을 반환한다`() {
        metrics.setConnectionState("binance", connected = true)
        val gauge = registry.find("ws.connection.state").tag("exchange", "binance").gauge()
        assertThat(gauge?.value()).isEqualTo(1.0)
    }

    @Test
    fun `setConnectionState false면 Gauge가 0을 반환한다`() {
        metrics.setConnectionState("binance", connected = true)
        metrics.setConnectionState("binance", connected = false)
        assertThat(registry.find("ws.connection.state").tag("exchange", "binance").gauge()?.value()).isEqualTo(0.0)
    }

    @Test
    fun `recordMessage 호출 시 Counter가 증가한다`() {
        metrics.recordMessage("binance")
        metrics.recordMessage("binance")
        assertThat(registry.find("ws.message.received").tag("exchange", "binance").counter()?.count()).isEqualTo(2.0)
    }

    @Test
    fun `recordReconnect 호출 시 Counter가 증가한다`() {
        metrics.recordReconnect("binance")
        assertThat(registry.find("ws.reconnect.attempt").tag("exchange", "binance").counter()?.count()).isEqualTo(1.0)
    }

    @Test
    fun `recordLag 호출 시 Timer에 기록된다`() {
        metrics.recordLag("binance", 12)
        val timer = registry.find("ws.message.lag.ms").tag("exchange", "binance").timer()
        assertThat(timer?.count()).isEqualTo(1L)
    }

    @Test
    fun `recordFirstMessageTimeout Counter 증가`() {
        metrics.recordFirstMessageTimeout("bithumb")
        assertThat(registry.find("ws.first.message.timeout").tag("exchange", "bithumb").counter()?.count()).isEqualTo(1.0)
    }

    @Test
    fun `recordOutOfOrder Counter 증가`() {
        metrics.recordOutOfOrder("bithumb")
        assertThat(registry.find("ws.out_of_order").tag("exchange", "bithumb").counter()?.count()).isEqualTo(1.0)
    }

    @Test
    fun `recordStale Counter 증가`() {
        metrics.recordStale("bithumb")
        assertThat(registry.find("ws.stale.bithumb").counter()?.count()).isEqualTo(1.0)
    }

    @Test
    fun `lastMessageAgeSeconds Gauge는 마지막 메시지 후 경과 초를 반환한다`() {
        val baseMs = 1_700_000_000_000L
        metrics.onMessageReceivedAt("binance", baseMs)
        val gauge = registry.find("ws.last.message.age").tag("exchange", "binance").gauge()
        assertThat(gauge).isNotNull
        val age = gauge!!.value()
        assertThat(age).isGreaterThanOrEqualTo(0.0)
    }
}
