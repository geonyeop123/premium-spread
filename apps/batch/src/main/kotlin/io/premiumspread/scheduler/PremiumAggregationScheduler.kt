package io.premiumspread.scheduler

import io.micrometer.core.instrument.MeterRegistry
import io.premiumspread.cache.PremiumCacheService
import io.premiumspread.redis.DistributedLockManager
import io.premiumspread.redis.RedisKeyGenerator
import io.premiumspread.redis.RedisTtl
import io.premiumspread.repository.PremiumAggregationRepository
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
 * 프리미엄 집계 스케줄러
 *
 * - 10초: 서머리 캐시 갱신 (1m, 10m, 1h, 1d)
 * - 1분: 초당 데이터 → 분 집계 → ZSet + DB
 * - 1시간: 분 데이터 → 시간 집계 → ZSet + DB
 * - 1일: 시간 데이터 → 일 집계 → DB
 */
@Component
class PremiumAggregationScheduler(
    private val premiumCacheService: PremiumCacheService,
    private val aggregationRepository: PremiumAggregationRepository,
    private val lockManager: DistributedLockManager,
    private val redisTemplate: StringRedisTemplate,
    private val meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val BTC = "btc"
        private const val LOCK_AGGREGATION = "lock:aggregation"
    }

    /**
     * 10초마다 서머리 캐시 갱신
     */
    @Scheduled(fixedRate = 10_000)
    fun updateSummaryCache() {
        try {
            val now = Instant.now()

            // 1분 서머리: 초당 데이터 기반
            premiumCacheService.calculateSummaryFromSeconds(
                BTC,
                now.minus(1, ChronoUnit.MINUTES),
                now,
            )?.let { summary ->
                premiumCacheService.saveSummary("1m", BTC, summary)
            }

            // 10분 서머리: 초당 데이터 기반 (5분 보관이므로 가능)
            premiumCacheService.calculateSummaryFromSeconds(
                BTC,
                now.minus(10, ChronoUnit.MINUTES),
                now,
            )?.let { summary ->
                premiumCacheService.saveSummary("10m", BTC, summary)
            }

            // 1시간 서머리: 분 집계 데이터 기반
            premiumCacheService.calculateSummaryFromMinutes(
                BTC,
                now.minus(1, ChronoUnit.HOURS),
                now,
            )?.let { summary ->
                premiumCacheService.saveSummary("1h", BTC, summary)
            }

            // 1일 서머리: 시간 집계 데이터 기반
            premiumCacheService.calculateSummaryFromHours(
                BTC,
                now.minus(24, ChronoUnit.HOURS),
                now,
            )?.let { summary ->
                premiumCacheService.saveSummary("1d", BTC, summary)
            }

            meterRegistry.counter("scheduler.summary.success").increment()
            log.debug("Updated summary caches")
        } catch (e: Exception) {
            meterRegistry.counter("scheduler.summary.error", "error", e.javaClass.simpleName).increment()
            log.error("Failed to update summary cache", e)
        }
    }

    /**
     * 1분마다 분 집계 (정각 기준)
     * cron: 매분 0초에 실행
     */
    @Scheduled(cron = "0 * * * * *")
    fun aggregateMinute() {
        val result = lockManager.withLock(
            lockKey = "$LOCK_AGGREGATION:minute",
            waitTime = 0,
            leaseTime = 30,
            timeUnit = TimeUnit.SECONDS,
        ) {
            try {
                val now = Instant.now()
                val minuteStart = now.minus(1, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MINUTES)
                val minuteEnd = minuteStart.plus(1, ChronoUnit.MINUTES)

                // 초당 데이터 집계
                val agg = premiumCacheService.aggregateSecondsData(BTC, minuteStart, minuteEnd)

                if (agg != null) {
                    // ZSet에 저장
                    premiumCacheService.saveToMinutes(BTC, minuteStart, agg)

                    // DB에 저장
                    val minuteAt = LocalDateTime.ofInstant(minuteStart, ZoneId.systemDefault())
                    aggregationRepository.saveMinute(BTC, minuteAt, agg)

                    meterRegistry.counter("scheduler.aggregation.minute.success").increment()
                    log.info(
                        "Aggregated minute data: {} at {} (high={}, low={}, count={})",
                        BTC, minuteAt, agg.high, agg.low, agg.count,
                    )
                } else {
                    meterRegistry.counter("scheduler.aggregation.minute.skipped", "reason", "no_data").increment()
                    log.warn("No data to aggregate for minute: {}", minuteStart)
                }

                // 마지막 실행 시각 기록
                updateLastRunTime("aggregation:minute")
            } catch (e: Exception) {
                meterRegistry.counter("scheduler.aggregation.minute.error", "error", e.javaClass.simpleName).increment()
                log.error("Failed to aggregate minute data", e)
                throw e
            }
        }

        if (result.isNotAcquired()) {
            log.trace("Minute aggregation skipped - lock not acquired")
        }
    }

    /**
     * 1시간마다 시간 집계 (정시 기준)
     * cron: 매시 0분 5초에 실행 (분 집계 완료 후)
     */
    @Scheduled(cron = "5 0 * * * *")
    fun aggregateHour() {
        val result = lockManager.withLock(
            lockKey = "$LOCK_AGGREGATION:hour",
            waitTime = 0,
            leaseTime = 60,
            timeUnit = TimeUnit.SECONDS,
        ) {
            try {
                val now = Instant.now()
                val hourStart = now.minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.HOURS)
                val hourEnd = hourStart.plus(1, ChronoUnit.HOURS)

                // 분 데이터 집계
                val agg = premiumCacheService.aggregateMinutesData(BTC, hourStart, hourEnd)

                if (agg != null) {
                    // ZSet에 저장
                    premiumCacheService.saveToHours(BTC, hourStart, agg)

                    // DB에 저장
                    val hourAt = LocalDateTime.ofInstant(hourStart, ZoneId.systemDefault())
                    aggregationRepository.saveHour(BTC, hourAt, agg)

                    meterRegistry.counter("scheduler.aggregation.hour.success").increment()
                    log.info(
                        "Aggregated hour data: {} at {} (high={}, low={}, count={})",
                        BTC, hourAt, agg.high, agg.low, agg.count,
                    )
                } else {
                    meterRegistry.counter("scheduler.aggregation.hour.skipped", "reason", "no_data").increment()
                    log.warn("No data to aggregate for hour: {}", hourStart)
                }

                updateLastRunTime("aggregation:hour")
            } catch (e: Exception) {
                meterRegistry.counter("scheduler.aggregation.hour.error", "error", e.javaClass.simpleName).increment()
                log.error("Failed to aggregate hour data", e)
                throw e
            }
        }

        if (result.isNotAcquired()) {
            log.trace("Hour aggregation skipped - lock not acquired")
        }
    }

    /**
     * 1일마다 일 집계 (자정 기준)
     * cron: 매일 00:00:10에 실행 (시간 집계 완료 후)
     */
    @Scheduled(cron = "10 0 0 * * *")
    fun aggregateDay() {
        val result = lockManager.withLock(
            lockKey = "$LOCK_AGGREGATION:day",
            waitTime = 0,
            leaseTime = 120,
            timeUnit = TimeUnit.SECONDS,
        ) {
            try {
                val now = Instant.now()
                val dayStart = now.minus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.DAYS)
                val dayEnd = dayStart.plus(1, ChronoUnit.DAYS)

                // 시간 데이터 집계
                val agg = premiumCacheService.aggregateHoursData(BTC, dayStart, dayEnd)

                if (agg != null) {
                    // DB에 저장 (ZSet은 저장하지 않음)
                    val dayAt = LocalDateTime.ofInstant(dayStart, ZoneId.systemDefault()).toLocalDate()
                    aggregationRepository.saveDay(BTC, dayAt, agg)

                    meterRegistry.counter("scheduler.aggregation.day.success").increment()
                    log.info(
                        "Aggregated day data: {} at {} (high={}, low={}, count={})",
                        BTC, dayAt, agg.high, agg.low, agg.count,
                    )
                } else {
                    meterRegistry.counter("scheduler.aggregation.day.skipped", "reason", "no_data").increment()
                    log.warn("No data to aggregate for day: {}", dayStart)
                }

                updateLastRunTime("aggregation:day")
            } catch (e: Exception) {
                meterRegistry.counter("scheduler.aggregation.day.error", "error", e.javaClass.simpleName).increment()
                log.error("Failed to aggregate day data", e)
                throw e
            }
        }

        if (result.isNotAcquired()) {
            log.trace("Day aggregation skipped - lock not acquired")
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
