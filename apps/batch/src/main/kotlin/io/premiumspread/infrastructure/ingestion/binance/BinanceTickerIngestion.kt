package io.premiumspread.infrastructure.ingestion.binance

import io.premiumspread.cache.TickerCacheService
import io.premiumspread.client.TickerData
import io.premiumspread.infrastructure.websocket.WebSocketMetrics
import io.premiumspread.monitoring.AlertService
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * 바이낸스 WebSocket으로 도착한 TickerData를 monotonic 검증 후 캐시에 저장한다.
 *
 * - `@miniTicker`는 1초 고정 push이므로 별도 down-sample 불필요. 메시지 수신 즉시 hash + 초ZSet 동시 저장.
 * - monotonic은 `updateAndGet` CAS로 atomic — 동시 메시지 race 차단.
 * - 캐시 write 실패 5회 연속 → critical alert (silent data loss 방지).
 */
@Component
@ConditionalOnProperty("premium.ingestion.binance.mode", havingValue = "websocket")
class BinanceTickerIngestion(
    private val tickerCacheService: TickerCacheService,
    private val metrics: WebSocketMetrics,
    private val alertService: AlertService,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val lastTimestamp = AtomicReference<Instant?>(null)
    private val consecutiveSaveFailures = AtomicInteger(0)

    fun onMessage(ticker: TickerData) {
        // Atomic monotonic CAS: strict ordering — 같거나 이전이면 폐기.
        // updateAndGet 람다는 retry될 수 있으므로 prev 캡처는 안전하지 않다. 대신 compareAndSet 루프로 명시.
        while (true) {
            val prev = lastTimestamp.get()
            if (prev != null && !ticker.timestamp.isAfter(prev)) {
                metrics.recordOutOfOrder(EXCHANGE)
                log.debug("Discard out-of-order binance ticker: prev={}, current={}", prev, ticker.timestamp)
                return
            }
            if (lastTimestamp.compareAndSet(prev, ticker.timestamp)) break
            // CAS 실패: 다른 스레드가 먼저 업데이트 → 재평가
        }

        val now = Instant.now(clock)
        val lagMs = Duration.between(ticker.timestamp, now).toMillis().coerceAtLeast(0)
        metrics.recordLag(EXCHANGE, lagMs)

        try {
            tickerCacheService.save(ticker)
            tickerCacheService.saveToSeconds(ticker)
            consecutiveSaveFailures.set(0)
        } catch (e: Exception) {
            metrics.recordFlushError(EXCHANGE, e)
            val failures = consecutiveSaveFailures.incrementAndGet()
            log.warn("Binance ingestion cache write failed (consecutive={}): {}", failures, e.message)
            if (failures >= FAILURE_ALERT_THRESHOLD) {
                alertService.sendCriticalAlert("[binance] WebSocket ingestion 캐시 저장 5회 연속 실패: ${e.message}")
                consecutiveSaveFailures.set(0)
            }
        }
    }

    companion object {
        private const val EXCHANGE = "binance"
        private const val FAILURE_ALERT_THRESHOLD = 5
    }
}
