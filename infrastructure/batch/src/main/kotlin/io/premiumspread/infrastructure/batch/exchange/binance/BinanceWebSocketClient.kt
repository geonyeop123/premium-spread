package io.premiumspread.infrastructure.batch.exchange.binance

import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.premiumspread.domain.job.OperatorAlert
import io.premiumspread.domain.market.MarketTickerStream
import io.premiumspread.domain.market.TickerSink
import io.premiumspread.infrastructure.batch.alert.sendCritical
import io.premiumspread.infrastructure.batch.exchange.TickerData
import io.premiumspread.infrastructure.batch.ingestion.binance.BinanceTickerIngestion
import io.premiumspread.infrastructure.batch.websocket.HeartbeatPolicy
import io.premiumspread.infrastructure.batch.websocket.WebSocketConnectionConfig
import io.premiumspread.infrastructure.batch.websocket.WebSocketConnectionManager
import io.premiumspread.infrastructure.batch.websocket.WebSocketMetrics
import io.premiumspread.infrastructure.batch.websocket.WebSocketStreamProperties
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * 바이낸스 BTCUSDT 무기한 선물 `@bookTicker` (best bid/ask 실시간 push) WebSocket 구독.
 *
 * - 연결 직후 별도 subscribe 메시지 불필요 (URL에 채널이 포함됨).
 * - 가격은 best bid/ask의 mid `(b + a) / 2`로 산정한다.
 * - 메시지마다 [BinanceTickerIngestion]로 위임.
 * - parse 실패는 `ws.parse.error{exchange=binance}` counter 증가 + 5회 연속 시 critical alert.
 */
@Component
@Profile("!test")
class BinanceWebSocketClient(
    private val ingestion: BinanceTickerIngestion,
    private val metrics: WebSocketMetrics,
    private val operatorAlert: OperatorAlert,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
    properties: WebSocketStreamProperties,
) : MarketTickerStream {
    private val log = LoggerFactory.getLogger(javaClass)
    private val consecutiveParseErrors = AtomicInteger(0)
    private val parseErrorCounter: Counter = Counter.builder("ws.parse.error")
        .tag("exchange", EXCHANGE)
        .register(meterRegistry)
    private val stream = properties.binance
    private val connection = properties.connection
    private val externalSink = AtomicReference<TickerSink?>()

    private val manager = WebSocketConnectionManager(
        config = WebSocketConnectionConfig(
            exchange = EXCHANGE,
            url = stream.endpoint.toASCIIString(),
            firstMessageTimeout = connection.firstMessageTimeout,
            idleTimeout = connection.idleTimeout,
            heartbeat = HeartbeatPolicy.ServerPingResponse,
            onMessage = ::handlePayload,
        ),
        metrics = metrics,
        operatorAlert = operatorAlert,
        initialBackoff = connection.initialBackoff,
        maxBackoff = connection.maxBackoff,
        watchdogCheckInterval = connection.watchdogInterval,
    )

    @PostConstruct
    fun startDefault() {
        log.info("Starting Binance WebSocket client: {}", stream.endpoint)
        manager.start()
    }

    override fun start(sink: TickerSink) {
        externalSink.set(sink)
        manager.start()
    }

    @PreDestroy
    override fun stop() {
        log.info("Stopping Binance WebSocket client")
        manager.stop()
    }

    internal fun handlePayload(payload: String) {
        val ticker = parse(payload)
        if (ticker == null) return
        consecutiveParseErrors.set(0)
        externalSink.get()?.accept(ticker.toMarketTick()) ?: ingestion.onMessage(ticker)
    }

    internal fun parse(payload: String): TickerData? {
        return try {
            val msg = objectMapper.readValue(payload, BinanceBookTickerMessage::class.java)
            val expectedPair = "${stream.symbol}${stream.quote}".uppercase()
            if (!msg.symbol.equals(expectedPair, ignoreCase = true)) {
                return recordParseError("unexpected pair: ${msg.symbol}, expected: $expectedPair")
            }
            val bid = msg.bestBid.toBigDecimalOrNull()
                ?: return recordParseError("invalid bestBid: ${msg.bestBid}")
            val ask = msg.bestAsk.toBigDecimalOrNull()
                ?: return recordParseError("invalid bestAsk: ${msg.bestAsk}")
            // 가격 = mid = (bestBid + bestAsk) / 2. scale·RoundingMode 명시 (defensive).
            val price = bid.add(ask).divide(BigDecimal(2), MID_PRICE_SCALE, RoundingMode.HALF_UP)
            TickerData(
                exchange = EXCHANGE_UPPER,
                symbol = stream.symbol.uppercase(),
                currency = stream.quote.uppercase(),
                price = price,
                volume = null,
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
            operatorAlert.sendCritical(
                code = "websocket.parse.failure",
                message = "[binance] WebSocket 메시지 parse 5회 연속 실패: $reason",
                occurredAt = Instant.ofEpochMilli(metrics.currentEpochMilli()),
                attributes = mapOf("exchange" to EXCHANGE),
            )
            consecutiveParseErrors.set(0)
        }
        return null
    }

    companion object {
        // 엔트리포인트는 `/public`. bookTicker는 `/market`에서 핸드셰이크만 되고 프레임 0건(silent failure)이며,
        // `/public/ws/<symbol>@bookTicker`만 실시간 프레임을 push한다 (endpoint probe로 검증).
        // 참고: miniTicker는 `/market`에서 동작했으나(#46/#51) bookTicker는 엔트리포인트가 다르다.
        private const val EXCHANGE = "binance"
        private const val EXCHANGE_UPPER = "BINANCE"

        // mid 계산 시 나눗셈 결과 정밀도 — BTC 선물 가격은 소수 1~2자리이므로 8자리면 충분.
        private const val MID_PRICE_SCALE = 8
        private const val PARSE_FAILURE_ALERT_THRESHOLD = 5
    }
}
