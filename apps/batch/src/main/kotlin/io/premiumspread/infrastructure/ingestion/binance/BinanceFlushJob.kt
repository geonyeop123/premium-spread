package io.premiumspread.infrastructure.ingestion.binance

import io.premiumspread.cache.TickerCacheService
import io.premiumspread.infrastructure.websocket.WebSocketMetrics
import io.premiumspread.monitoring.AlertService
import io.premiumspread.redis.RedisTtl
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/**
 * 바이낸스 WebSocket(@bookTicker)으로 받은 최신 ticker를 1초 주기로 ZSet에 flush 한다.
 *
 * - Hash는 [BinanceTickerIngestion]이 메시지 수신 시점에 이미 갱신 → 본 job은 ZSet만 갱신.
 * - score는 flush 시점(`Instant.now(clock)`)을 사용 — exchange timestamp가 변하지 않아도 distinct score 보장.
 * - 마지막 메시지가 10초 이상 지난 경우 stale 처리하고 skip.
 * - flush 실패 5회 연속 발생 시 critical alert.
 */
@Component
@ConditionalOnProperty("premium.ingestion.binance.mode", havingValue = "websocket")
class BinanceFlushJob(
    private val ingestion: BinanceTickerIngestion,
    private val tickerCacheService: TickerCacheService,
    private val metrics: WebSocketMetrics,
    private val alertService: AlertService,
    private val redisTemplate: StringRedisTemplate,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val consecutiveFailures = AtomicInteger(0)

    fun run() {
        val latest = ingestion.latest() ?: return
        val now = Instant.now(clock)
        val age = Duration.between(latest.receivedAt, now)
        if (age > STALE_THRESHOLD) {
            metrics.recordStale(EXCHANGE)
            return
        }
        try {
            tickerCacheService.saveToSecondsWithScore(latest.ticker, now)
            redisTemplate.opsForValue().set(LAST_RUN_KEY, now.toEpochMilli().toString(), RedisTtl.BATCH_HEALTH)
            metrics.recordFlush(EXCHANGE)
            consecutiveFailures.set(0)
        } catch (e: Exception) {
            metrics.recordFlushError(EXCHANGE, e)
            val failures = consecutiveFailures.incrementAndGet()
            log.warn("Binance flush failed (consecutive={}): {}", failures, e.message)
            if (failures >= FAILURE_ALERT_THRESHOLD) {
                alertService.sendCriticalAlert("[binance] flush 5회 연속 실패: ${e.message}")
                consecutiveFailures.set(0)
            }
        }
    }

    companion object {
        const val EXCHANGE = "binance"
        val STALE_THRESHOLD: Duration = Duration.ofSeconds(10)
        const val FAILURE_ALERT_THRESHOLD = 5
        const val LAST_RUN_KEY = "batch:last-run:binance-flush"
    }
}
