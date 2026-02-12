package io.premiumspread.scheduler

import io.premiumspread.application.common.JobConfig
import io.premiumspread.application.common.JobExecutor
import io.premiumspread.application.job.aggregation.AggregationJob
import io.premiumspread.cache.PremiumCacheService
import io.premiumspread.redis.AggregationTimeUnit
import io.premiumspread.repository.PremiumAggregation
import io.premiumspread.repository.PremiumAggregationRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

@Component
class PremiumAggregationScheduler(
    private val premiumCacheService: PremiumCacheService,
    private val aggregationRepository: PremiumAggregationRepository,
    private val jobExecutor: JobExecutor,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val BTC = "btc"
        private const val LOCK_AGGREGATION = "lock:aggregation"

        private val MINUTE_CONFIG = JobConfig("aggregation:minute", "$LOCK_AGGREGATION:minute", 30, TimeUnit.SECONDS)
        private val HOUR_CONFIG = JobConfig("aggregation:hour", "$LOCK_AGGREGATION:hour", 60, TimeUnit.SECONDS)
        private val DAY_CONFIG = JobConfig("aggregation:day", "$LOCK_AGGREGATION:day", 120, TimeUnit.SECONDS)
    }

    private val minuteJob = AggregationJob<PremiumAggregation>(
        reader = { from, to -> premiumCacheService.aggregateSecondsData(BTC, from, to) },
        writer = { agg, from, _ ->
            premiumCacheService.saveAggregation(AggregationTimeUnit.MINUTES, BTC, from, agg)
            val minuteAt = LocalDateTime.ofInstant(from, ZoneId.systemDefault())
            aggregationRepository.saveMinute(BTC, minuteAt, agg)
            log.info("Aggregated minute data: {} at {} (high={}, low={}, count={})", BTC, minuteAt, agg.high, agg.low, agg.count)
        },
        unit = ChronoUnit.MINUTES,
    )

    private val hourJob = AggregationJob<PremiumAggregation>(
        reader = { from, to -> premiumCacheService.aggregateData(AggregationTimeUnit.MINUTES, BTC, from, to) },
        writer = { agg, from, _ ->
            premiumCacheService.saveAggregation(AggregationTimeUnit.HOURS, BTC, from, agg)
            val hourAt = LocalDateTime.ofInstant(from, ZoneId.systemDefault())
            aggregationRepository.saveHour(BTC, hourAt, agg)
            log.info("Aggregated hour data: {} at {} (high={}, low={}, count={})", BTC, hourAt, agg.high, agg.low, agg.count)
        },
        unit = ChronoUnit.HOURS,
    )

    private val dayJob = AggregationJob<PremiumAggregation>(
        reader = { from, to -> premiumCacheService.aggregateData(AggregationTimeUnit.HOURS, BTC, from, to) },
        writer = { agg, from, _ ->
            val dayAt = LocalDateTime.ofInstant(from, ZoneId.systemDefault()).toLocalDate()
            aggregationRepository.saveDay(BTC, dayAt, agg)
            log.info("Aggregated day data: {} at {} (high={}, low={}, count={})", BTC, dayAt, agg.high, agg.low, agg.count)
        },
        unit = ChronoUnit.DAYS,
    )

    @Scheduled(fixedRate = 10_000)
    fun updateSummaryCache() {
        try {
            val now = Instant.now()

            premiumCacheService.calculateSummaryFromSeconds(BTC, now.minus(1, ChronoUnit.MINUTES), now)
                ?.let { premiumCacheService.saveSummary("1m", BTC, it) }

            premiumCacheService.calculateSummaryFromSeconds(BTC, now.minus(10, ChronoUnit.MINUTES), now)
                ?.let { premiumCacheService.saveSummary("10m", BTC, it) }

            premiumCacheService.calculateSummary(AggregationTimeUnit.MINUTES, BTC, now.minus(1, ChronoUnit.HOURS), now)
                ?.let { premiumCacheService.saveSummary("1h", BTC, it) }

            premiumCacheService.calculateSummary(AggregationTimeUnit.HOURS, BTC, now.minus(24, ChronoUnit.HOURS), now)
                ?.let { premiumCacheService.saveSummary("1d", BTC, it) }

            log.debug("Updated summary caches")
        } catch (e: Exception) {
            log.error("Failed to update summary cache", e)
        }
    }

    @Scheduled(cron = "0 * * * * *")
    fun aggregateMinute() {
        jobExecutor.execute(MINUTE_CONFIG) { minuteJob.run() }
    }

    @Scheduled(cron = "5 0 * * * *")
    fun aggregateHour() {
        jobExecutor.execute(HOUR_CONFIG) { hourJob.run() }
    }

    @Scheduled(cron = "10 0 0 * * *")
    fun aggregateDay() {
        jobExecutor.execute(DAY_CONFIG) { dayJob.run() }
    }
}
