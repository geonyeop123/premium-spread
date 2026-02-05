package io.premiumspread.scheduler

import io.micrometer.core.instrument.MeterRegistry
import io.premiumspread.cache.TickerCacheService
import io.premiumspread.redis.DistributedLockManager
import io.premiumspread.redis.RedisKeyGenerator
import io.premiumspread.redis.RedisTtl
import io.premiumspread.redis.TickerAggregationTimeUnit
import io.premiumspread.repository.TickerAggregationRepository
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

/**
 * Ticker 집계 스케줄러
 *
 * - 1분: 초당 데이터 → 분 집계 → ZSet + DB
 * - 1시간: 분 데이터 → 시간 집계 → ZSet + DB
 * - 1일: 시간 데이터 → 일 집계 → DB
 */
@Component
class TickerAggregationScheduler(
    private val tickerCacheService: TickerCacheService,
    private val aggregationRepository: TickerAggregationRepository,
    private val lockManager: DistributedLockManager,
    private val redisTemplate: StringRedisTemplate,
    private val meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val LOCK_TICKER_AGGREGATION = "lock:ticker:aggregation"

        // 집계 대상 거래소/심볼 목록
        private val TARGETS = listOf(
            "bithumb" to "btc",
            "binance" to "btc",
        )
    }

    /**
     * 1분마다 분 집계 (정각 기준)
     * cron: 매분 2초에 실행 (Premium 집계와 겹치지 않도록)
     */
    @Scheduled(cron = "2 * * * * *")
    fun aggregateMinute() {
        val result = lockManager.withLock(
            lockKey = "$LOCK_TICKER_AGGREGATION:minute",
            waitTime = 0,
            leaseTime = 30,
            timeUnit = TimeUnit.SECONDS,
        ) {
            try {
                val now = Instant.now()
                val minuteStart = now.minus(1, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MINUTES)
                val minuteEnd = minuteStart.plus(1, ChronoUnit.MINUTES)

                for ((exchange, symbol) in TARGETS) {
                    val agg = tickerCacheService.aggregateSecondsData(exchange, symbol, minuteStart, minuteEnd)

                    if (agg != null) {
                        // ZSet에 저장
                        tickerCacheService.saveAggregation(
                            TickerAggregationTimeUnit.MINUTES,
                            exchange,
                            symbol,
                            minuteStart,
                            agg,
                        )

                        // DB에 저장
                        val minuteAt = LocalDateTime.ofInstant(minuteStart, ZoneId.systemDefault())
                        aggregationRepository.saveMinute(exchange, symbol, minuteAt, agg)

                        meterRegistry.counter("scheduler.ticker.aggregation.minute.success", "exchange", exchange).increment()
                        log.info(
                            "Aggregated ticker minute data: {}:{} at {} (high={}, low={}, count={})",
                            exchange, symbol, minuteAt, agg.high, agg.low, agg.count,
                        )
                    } else {
                        meterRegistry.counter("scheduler.ticker.aggregation.minute.skipped", "exchange", exchange, "reason", "no_data").increment()
                        log.warn("No ticker data to aggregate for minute: {}:{} at {}", exchange, symbol, minuteStart)
                    }
                }

                updateLastRunTime("ticker:aggregation:minute")
            } catch (e: Exception) {
                meterRegistry.counter("scheduler.ticker.aggregation.minute.error", "error", e.javaClass.simpleName).increment()
                log.error("Failed to aggregate ticker minute data", e)
                throw e
            }
        }

        if (result.isNotAcquired()) {
            log.trace("Ticker minute aggregation skipped - lock not acquired")
        }
    }

    /**
     * 1시간마다 시간 집계 (정시 기준)
     * cron: 매시 0분 7초에 실행 (분 집계 완료 후)
     */
    @Scheduled(cron = "7 0 * * * *")
    fun aggregateHour() {
        val result = lockManager.withLock(
            lockKey = "$LOCK_TICKER_AGGREGATION:hour",
            waitTime = 0,
            leaseTime = 60,
            timeUnit = TimeUnit.SECONDS,
        ) {
            try {
                val now = Instant.now()
                val hourStart = now.minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.HOURS)
                val hourEnd = hourStart.plus(1, ChronoUnit.HOURS)

                for ((exchange, symbol) in TARGETS) {
                    val agg = tickerCacheService.aggregateData(
                        TickerAggregationTimeUnit.MINUTES,
                        exchange,
                        symbol,
                        hourStart,
                        hourEnd,
                    )

                    if (agg != null) {
                        // ZSet에 저장
                        tickerCacheService.saveAggregation(
                            TickerAggregationTimeUnit.HOURS,
                            exchange,
                            symbol,
                            hourStart,
                            agg,
                        )

                        // DB에 저장
                        val hourAt = LocalDateTime.ofInstant(hourStart, ZoneId.systemDefault())
                        aggregationRepository.saveHour(exchange, symbol, hourAt, agg)

                        meterRegistry.counter("scheduler.ticker.aggregation.hour.success", "exchange", exchange).increment()
                        log.info(
                            "Aggregated ticker hour data: {}:{} at {} (high={}, low={}, count={})",
                            exchange, symbol, hourAt, agg.high, agg.low, agg.count,
                        )
                    } else {
                        meterRegistry.counter("scheduler.ticker.aggregation.hour.skipped", "exchange", exchange, "reason", "no_data").increment()
                        log.warn("No ticker data to aggregate for hour: {}:{} at {}", exchange, symbol, hourStart)
                    }
                }

                updateLastRunTime("ticker:aggregation:hour")
            } catch (e: Exception) {
                meterRegistry.counter("scheduler.ticker.aggregation.hour.error", "error", e.javaClass.simpleName).increment()
                log.error("Failed to aggregate ticker hour data", e)
                throw e
            }
        }

        if (result.isNotAcquired()) {
            log.trace("Ticker hour aggregation skipped - lock not acquired")
        }
    }

    /**
     * 1일마다 일 집계 (자정 기준)
     * cron: 매일 00:00:12에 실행 (시간 집계 완료 후)
     */
    @Scheduled(cron = "12 0 0 * * *")
    fun aggregateDay() {
        val result = lockManager.withLock(
            lockKey = "$LOCK_TICKER_AGGREGATION:day",
            waitTime = 0,
            leaseTime = 120,
            timeUnit = TimeUnit.SECONDS,
        ) {
            try {
                val now = Instant.now()
                val dayStart = now.minus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.DAYS)
                val dayEnd = dayStart.plus(1, ChronoUnit.DAYS)

                for ((exchange, symbol) in TARGETS) {
                    val agg = tickerCacheService.aggregateData(
                        TickerAggregationTimeUnit.HOURS,
                        exchange,
                        symbol,
                        dayStart,
                        dayEnd,
                    )

                    if (agg != null) {
                        // DB에 저장 (ZSet은 저장하지 않음)
                        val dayAt = LocalDateTime.ofInstant(dayStart, ZoneId.systemDefault()).toLocalDate()
                        aggregationRepository.saveDay(exchange, symbol, dayAt, agg)

                        meterRegistry.counter("scheduler.ticker.aggregation.day.success", "exchange", exchange).increment()
                        log.info(
                            "Aggregated ticker day data: {}:{} at {} (high={}, low={}, count={})",
                            exchange, symbol, dayAt, agg.high, agg.low, agg.count,
                        )
                    } else {
                        meterRegistry.counter("scheduler.ticker.aggregation.day.skipped", "exchange", exchange, "reason", "no_data").increment()
                        log.warn("No ticker data to aggregate for day: {}:{} at {}", exchange, symbol, dayStart)
                    }
                }

                updateLastRunTime("ticker:aggregation:day")
            } catch (e: Exception) {
                meterRegistry.counter("scheduler.ticker.aggregation.day.error", "error", e.javaClass.simpleName).increment()
                log.error("Failed to aggregate ticker day data", e)
                throw e
            }
        }

        if (result.isNotAcquired()) {
            log.trace("Ticker day aggregation skipped - lock not acquired")
        }
    }

    private fun updateLastRunTime(job: String) {
        redisTemplate.opsForValue().set(
            RedisKeyGenerator.batchLastRunKey(job),
            System.currentTimeMillis().toString(),
            RedisTtl.BATCH_HEALTH,
        )
    }
}
