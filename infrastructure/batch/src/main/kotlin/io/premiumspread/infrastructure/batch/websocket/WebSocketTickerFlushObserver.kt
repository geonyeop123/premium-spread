package io.premiumspread.infrastructure.batch.websocket

import io.premiumspread.domain.market.TickerFlushObserver
import io.premiumspread.domain.ticker.Exchange

/** Application flush 경로에서 기존 WebSocket/ticker metric 계약을 유지한다. */
class WebSocketTickerFlushObserver(
    private val metrics: WebSocketMetrics,
) : TickerFlushObserver {
    override fun stale(exchange: Exchange) {
        metrics.recordStale(exchange.metricTag())
    }

    override fun succeeded(exchange: Exchange) {
        metrics.recordFlush(exchange.metricTag())
    }

    override fun failed(exchange: Exchange, exception: Exception) {
        metrics.recordFlushError(exchange.metricTag(), exception)
    }

    private fun Exchange.metricTag(): String = name.lowercase()
}
