package io.premiumspread.infrastructure.batch.ingestion.binance

import io.premiumspread.infrastructure.batch.cache.TickerCacheService
import io.premiumspread.infrastructure.batch.exchange.TickerData
import io.premiumspread.infrastructure.batch.websocket.WebSocketMetrics
import io.premiumspread.domain.job.OperatorAlert
import io.premiumspread.infrastructure.batch.alert.sendCritical
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * 바이낸스 WebSocket(@bookTicker)으로 도착한 TickerData를 in-memory에 보관하고 hash를 갱신한다.
 *
 * - hash 갱신은 메시지 수신 시점 (exchange timestamp 그대로). TTL freshness 의미 보존.
 * - ZSet 저장은 Application flush job이 1초 주기로 처리 (down-sample) — bookTicker는 초당 수십~수백 건 push.
 * - monotonic check는 `updateAndGet` CAS로 atomic — strict하게 오래된 메시지만 폐기.
 *   같은 ms 타임스탬프는 수용 (bookTicker는 동일 eventTime ms에 복수 push가 정상).
 * - 캐시 저장 실패 5회 연속 → critical alert.
 */
@Component
@Profile("!test")
class BinanceTickerIngestion(
    private val tickerCacheService: TickerCacheService,
    private val metrics: WebSocketMetrics,
    private val operatorAlert: OperatorAlert,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val lastTicker = AtomicReference<LatestTicker?>(null)
    private val consecutiveSaveFailures = AtomicInteger(0)

    data class LatestTicker(val ticker: TickerData, val receivedAt: Instant)

    fun onMessage(ticker: TickerData) {
        val now = clock.instant()
        val candidate = LatestTicker(ticker, now)

        // Atomic monotonic CAS — strict하게 오래된 메시지만 폐기. 같은 ms 타임스탬프는 수용
        // (bookTicker는 동일 eventTime ms에 복수 메시지가 정상).
        val updated = lastTicker.updateAndGet { prev ->
            if (prev != null && ticker.timestamp.isBefore(prev.ticker.timestamp)) prev else candidate
        }
        if (updated !== candidate) {
            metrics.recordOutOfOrder(EXCHANGE)
            log.debug(
                "Discard out-of-order binance ticker: prev={}, current={}",
                updated?.ticker?.timestamp,
                ticker.timestamp,
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
            log.warn("Binance ingestion hash save failed (consecutive={}): {}", failures, e.message)
            if (failures >= FAILURE_ALERT_THRESHOLD) {
                operatorAlert.sendCritical(
                    code = "websocket.ingestion.failure",
                    message = "[binance] WebSocket ingestion hash 저장 5회 연속 실패: ${e.message}",
                    occurredAt = now,
                    attributes = mapOf("exchange" to EXCHANGE),
                )
                consecutiveSaveFailures.set(0)
            }
        }
    }

    fun latest(): LatestTicker? = lastTicker.get()

    companion object {
        private const val EXCHANGE = "binance"
        private const val FAILURE_ALERT_THRESHOLD = 5
    }
}
