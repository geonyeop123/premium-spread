package io.premiumspread.infrastructure.ingestion.bithumb

import io.premiumspread.cache.TickerCacheService
import io.premiumspread.client.TickerData
import io.premiumspread.infrastructure.websocket.WebSocketMetrics
import io.premiumspread.monitoring.AlertService
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * 빗썸 WebSocket으로 도착한 TickerData를 in-memory에 보관하고 hash를 갱신한다.
 *
 * - hash 갱신은 메시지 수신 시점 (exchange timestamp 그대로). TTL 5s freshness 의미 보존.
 * - ZSet 저장은 [BithumbFlushJob]이 1초 주기로 처리 (down-sample).
 * - monotonic check는 `updateAndGet` CAS로 atomic 처리 — 동시 메시지의 race 차단.
 * - 캐시 저장 실패 5회 연속 → critical alert.
 */
@Component
@Profile("!test")
class BithumbTickerIngestion(
    private val tickerCacheService: TickerCacheService,
    private val metrics: WebSocketMetrics,
    private val alertService: AlertService,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val lastTicker = AtomicReference<LatestTicker?>(null)
    private val consecutiveSaveFailures = AtomicInteger(0)

    data class LatestTicker(val ticker: TickerData, val receivedAt: Instant)

    fun onMessage(ticker: TickerData) {
        val now = Instant.now(clock)
        val candidate = LatestTicker(ticker, now)

        // Atomic monotonic CAS — strict하게 오래된 메시지만 폐기. 같은 second 타임스탬프는 수용
        // (Bithumb 응답은 HHmmss 정밀도이므로 동일 second 내 새 메시지가 정상).
        val updated = lastTicker.updateAndGet { prev ->
            if (prev != null && ticker.timestamp.isBefore(prev.ticker.timestamp)) prev else candidate
        }
        if (updated !== candidate) {
            metrics.recordOutOfOrder(EXCHANGE)
            log.debug(
                "Discard out-of-order bithumb ticker: prev={}, current={}",
                updated?.ticker?.timestamp, ticker.timestamp,
            )
            return
        }

        // lag 메트릭 (exchange timestamp → now)
        val lagMs = Duration.between(ticker.timestamp, now).toMillis().coerceAtLeast(0)
        metrics.recordLag(EXCHANGE, lagMs)

        // Hash 저장 — 실패 시 메트릭 + threshold alert (lastTicker는 이미 갱신됐으므로 ZSet은 다음 flush에서 가능)
        try {
            tickerCacheService.save(ticker)
            consecutiveSaveFailures.set(0)
        } catch (e: Exception) {
            metrics.recordFlushError(EXCHANGE, e)
            val failures = consecutiveSaveFailures.incrementAndGet()
            log.warn("Bithumb hash save failed (consecutive={}): {}", failures, e.message)
            if (failures >= FAILURE_ALERT_THRESHOLD) {
                alertService.sendCriticalAlert("[bithumb] WebSocket ingestion hash 저장 5회 연속 실패: ${e.message}")
                consecutiveSaveFailures.set(0)
            }
        }
    }

    fun latest(): LatestTicker? = lastTicker.get()

    companion object {
        const val EXCHANGE = "bithumb"
        private const val FAILURE_ALERT_THRESHOLD = 5
    }
}
