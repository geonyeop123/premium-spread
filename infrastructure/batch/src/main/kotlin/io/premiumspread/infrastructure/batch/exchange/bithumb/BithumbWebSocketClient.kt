package io.premiumspread.infrastructure.batch.exchange.bithumb

import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.premiumspread.domain.job.OperatorAlert
import io.premiumspread.domain.market.MarketTickerStream
import io.premiumspread.domain.market.TickerSink
import io.premiumspread.infrastructure.batch.alert.sendCritical
import io.premiumspread.infrastructure.batch.exchange.TickerData
import io.premiumspread.infrastructure.batch.ingestion.bithumb.BithumbTickerIngestion
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
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * 빗썸 BTC_KRW 현물 ticker WebSocket 구독.
 *
 * - URL: wss://pubwss.bithumb.com/pub/ws
 * - 연결 직후 subscribe 메시지: {"type":"ticker","symbols":["BTC_KRW"],"tickTypes":["24H"]}
 * - idle 60초 종료 정책 대응 → ClientPing 30s 주기.
 */
@Component
@Profile("!test")
class BithumbWebSocketClient(
    private val ingestion: BithumbTickerIngestion,
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
    private val stream = properties.bithumb
    private val connection = properties.connection
    private val externalSink = AtomicReference<TickerSink?>()

    private val manager = WebSocketConnectionManager(
        config = WebSocketConnectionConfig(
            exchange = EXCHANGE,
            url = stream.endpoint.toASCIIString(),
            subscribeMessage = subscribeMessage(stream),
            firstMessageTimeout = connection.firstMessageTimeout,
            idleTimeout = connection.idleTimeout,
            heartbeat = HeartbeatPolicy.ClientPing(interval = PING_INTERVAL, pingMessage = PING_MESSAGE),
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
        log.info("Starting Bithumb WebSocket client: {}", stream.endpoint)
        manager.start()
    }

    override fun start(sink: TickerSink) {
        externalSink.set(sink)
        manager.start()
    }

    @PreDestroy
    override fun stop() {
        log.info("Stopping Bithumb WebSocket client")
        manager.stop()
    }

    internal fun handlePayload(payload: String) {
        val ticker = parse(payload)
        if (ticker == null) {
            // 구독 응답(`type != "ticker"`) 등 정상 흐름은 parse가 null 반환하지만 parse 실패와 구분 불가하므로
            // 여기서는 모두 "수용 불가 메시지"로 취급. ticker 수신 빈도가 압도적이라 노이즈 영향 미미.
            // 정확히 구분하려면 parse가 sealed result 반환하도록 확장 필요.
            return
        }
        consecutiveParseErrors.set(0)
        externalSink.get()?.accept(ticker.toMarketTick()) ?: ingestion.onMessage(ticker)
    }

    // Every validation return drops an untrusted websocket payload before it reaches the ingestion port.
    @Suppress("ReturnCount")
    internal fun parse(payload: String): TickerData? {
        return try {
            val msg = objectMapper.readValue(payload, BithumbWebSocketTickerMessage::class.java)
            if (msg.type != "ticker") return null
            val content = msg.content ?: return null
            val price = content.closePrice.toBigDecimalOrNull()
                ?: return recordParseError("invalid price: ${content.closePrice}")
            if (!content.symbol.equals(stream.pairCode(), ignoreCase = true)) {
                return recordParseError("unexpected symbol: ${content.symbol}")
            }
            val symbol = stream.symbol.uppercase()
            val currency = stream.quote.uppercase()
            // exchange timestamp 파싱 실패 시 메시지 폐기 (현재 시각 합성 금지 — stale/replay 검출 정확성 보존)
            val timestamp = parseTimestamp(content.date, content.time)
                ?: return recordParseError("missing or malformed date/time: ${content.date} ${content.time}")

            TickerData(
                exchange = EXCHANGE_UPPER,
                symbol = symbol,
                currency = currency,
                price = price,
                volume = content.volume?.toBigDecimalOrNull(),
                timestamp = timestamp,
            )
        } catch (e: Exception) {
            recordParseError("exception: ${e.javaClass.simpleName} ${e.message}")
        }
    }

    private fun recordParseError(reason: String): TickerData? {
        parseErrorCounter.increment()
        val failures = consecutiveParseErrors.incrementAndGet()
        log.warn("Bithumb parse error ({}): {}", failures, reason)
        if (failures >= PARSE_FAILURE_ALERT_THRESHOLD) {
            operatorAlert.sendCritical(
                code = "websocket.parse.failure",
                message = "[bithumb] WebSocket 메시지 parse 5회 연속 실패: $reason",
                occurredAt = Instant.ofEpochMilli(metrics.currentEpochMilli()),
                attributes = mapOf("exchange" to EXCHANGE),
            )
            consecutiveParseErrors.set(0)
        }
        return null
    }

    private fun parseTimestamp(date: String?, time: String?): Instant? {
        if (date.isNullOrBlank() || time.isNullOrBlank()) return null
        return try {
            val formatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
            val ldt = LocalDateTime.parse(date + time, formatter)
            ldt.atZone(ZoneId.of("Asia/Seoul")).toInstant()
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        const val PING_MESSAGE = """{"type":"ping"}"""
        private const val EXCHANGE = "bithumb"
        private const val EXCHANGE_UPPER = "BITHUMB"
        private const val PARSE_FAILURE_ALERT_THRESHOLD = 5
        val PING_INTERVAL: Duration = Duration.ofSeconds(30)
    }

    private fun subscribeMessage(stream: WebSocketStreamProperties.Stream): String =
        """{"type":"ticker","symbols":["${stream.pairCode()}"],"tickTypes":["24H"]}"""
}
