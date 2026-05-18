package io.premiumspread.client.binance

import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.premiumspread.client.TickerData
import io.premiumspread.infrastructure.ingestion.binance.BinanceTickerIngestion
import io.premiumspread.infrastructure.websocket.HeartbeatPolicy
import io.premiumspread.infrastructure.websocket.WebSocketConnectionConfig
import io.premiumspread.infrastructure.websocket.WebSocketConnectionManager
import io.premiumspread.infrastructure.websocket.WebSocketMetrics
import io.premiumspread.monitoring.AlertService
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/**
 * 바이낸스 BTCUSDT 무기한 선물 `@miniTicker` (1초 push) WebSocket 구독.
 *
 * - 연결 직후 별도 subscribe 메시지 불필요 (URL에 채널이 포함됨).
 * - 메시지마다 [BinanceTickerIngestion]로 위임.
 * - parse 실패는 `ws.parse.error{exchange=binance}` counter 증가 + 5회 연속 시 critical alert.
 */
@Component
@ConditionalOnProperty("premium.ingestion.binance.mode", havingValue = "websocket")
class BinanceWebSocketClient(
    private val ingestion: BinanceTickerIngestion,
    private val metrics: WebSocketMetrics,
    private val alertService: AlertService,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val consecutiveParseErrors = AtomicInteger(0)
    private val parseErrorCounter: Counter = Counter.builder("ws.parse.error")
        .tag("exchange", EXCHANGE)
        .register(meterRegistry)

    private val manager = WebSocketConnectionManager(
        config = WebSocketConnectionConfig(
            exchange = EXCHANGE,
            url = URL,
            heartbeat = HeartbeatPolicy.ServerPingResponse,
            onMessage = ::handlePayload,
        ),
        metrics = metrics,
        alertService = alertService,
    )

    @PostConstruct
    fun start() {
        log.info("Starting Binance WebSocket client: {}", URL)
        manager.start()
    }

    @PreDestroy
    fun stop() {
        log.info("Stopping Binance WebSocket client")
        manager.stop()
    }

    internal fun handlePayload(payload: String) {
        val ticker = parse(payload)
        if (ticker == null) return
        consecutiveParseErrors.set(0)
        ingestion.onMessage(ticker)
    }

    internal fun parse(payload: String): TickerData? {
        return try {
            val msg = objectMapper.readValue(payload, BinanceMiniTickerMessage::class.java)
            val price = msg.close.toBigDecimalOrNull()
                ?: return recordParseError("invalid price: ${msg.close}")
            TickerData(
                exchange = EXCHANGE_UPPER,
                symbol = extractBaseSymbol(msg.symbol),
                currency = CURRENCY,
                price = price,
                volume = msg.volume?.toBigDecimalOrNull(),
                timestamp = Instant.ofEpochMilli(msg.eventTime),
            )
        } catch (e: Exception) {
            recordParseError("exception: ${e.javaClass.simpleName} ${e.message}")
        }
    }

    private fun recordParseError(reason: String): TickerData? {
        parseErrorCounter.increment()
        val failures = consecutiveParseErrors.incrementAndGet()
        log.warn("Binance parse error ({}): {}", failures, reason)
        if (failures >= PARSE_FAILURE_ALERT_THRESHOLD) {
            alertService.sendCriticalAlert("[binance] WebSocket 메시지 parse 5회 연속 실패: $reason")
            consecutiveParseErrors.set(0)
        }
        return null
    }

    private fun extractBaseSymbol(symbol: String): String = when {
        symbol.endsWith("USDT") -> symbol.dropLast(4)
        symbol.endsWith("USD") -> symbol.dropLast(3)
        else -> symbol
    }

    companion object {
        const val URL = "wss://fstream.binance.com/market/ws/btcusdt@miniTicker"
        private const val EXCHANGE = "binance"
        private const val EXCHANGE_UPPER = "BINANCE"
        // issue spec 준수: 다운스트림 TickerAggregationScheduler가 "USD"로 조회하므로 통일.
        private const val CURRENCY = "USD"
        private const val PARSE_FAILURE_ALERT_THRESHOLD = 5
    }
}
