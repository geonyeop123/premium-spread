package io.premiumspread.infrastructure.batch.websocket

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.premiumspread.domain.ticker.Exchange
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.dao.DataAccessResourceFailureException
import java.time.Clock

class WebSocketTickerFlushObserverTest {
    private val registry = SimpleMeterRegistry()
    private val observer = WebSocketTickerFlushObserver(WebSocketMetrics(registry, Clock.systemUTC()))

    @Test
    fun `stale과 flush 결과를 bounded exchange tag로 기록한다`() {
        observer.stale(Exchange.BINANCE)
        observer.succeeded(Exchange.BINANCE)
        observer.failed(Exchange.BITHUMB, DataAccessResourceFailureException("down"))

        assertThat(registry.counter("ws.stale", "exchange", "binance").count()).isEqualTo(1.0)
        assertThat(
            registry.counter(
                "ticker.flush",
                "exchange",
                "binance",
                "outcome",
                "success",
                "error",
                "none",
            ).count(),
        ).isEqualTo(1.0)
        assertThat(
            registry.counter(
                "ticker.flush",
                "exchange",
                "bithumb",
                "outcome",
                "failure",
                "error",
                "data_access",
            ).count(),
        ).isEqualTo(1.0)
    }
}
