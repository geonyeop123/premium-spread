package io.premiumspread.scheduler

import io.premiumspread.application.common.JobConfig
import io.premiumspread.application.common.JobExecutor
import io.premiumspread.application.common.JobResult
import io.premiumspread.application.job.aggregation.AggregationJob
import io.premiumspread.cache.TickerCacheService
import io.premiumspread.redis.TickerAggregationTimeUnit
import io.premiumspread.repository.TickerAggregation
import io.premiumspread.repository.TickerAggregationRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

@Component
class TickerAggregationScheduler(
    private val tickerCacheService: TickerCacheService,
    private val aggregationRepository: TickerAggregationRepository,
    private val jobExecutor: JobExecutor,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val LOCK_TICKER_AGGREGATION = "lock:ticker:aggregation"

        private val TARGETS = listOf(
            "bithumb" to "btc",
            "binance" to "btc",
        )

        private val MINUTE_CONFIG = JobConfig(
            jobName = "ticker:aggregation:minute",
            lockKey = "$LOCK_TICKER_AGGREGATION:minute",
            leaseTime = 30,
            leaseTimeUnit = TimeUnit.SECONDS,
        )
        private val HOUR_CONFIG = JobConfig(
            jobName = "ticker:aggregation:hour",
            lockKey = "$LOCK_TICKER_AGGREGATION:hour",
            leaseTime = 60,
            leaseTimeUnit = TimeUnit.SECONDS,
        )
        private val DAY_CONFIG = JobConfig(
            jobName = "ticker:aggregation:day",
            lockKey = "$LOCK_TICKER_AGGREGATION:day",
            leaseTime = 120,
            leaseTimeUnit = TimeUnit.SECONDS,
        )
    }

    private fun minuteJobFor(exchange: String, symbol: String) = AggregationJob<TickerAggregation>(
        reader = { from, to -> tickerCacheService.aggregateSecondsData(exchange, symbol, from, to) },
        writer = { agg, from, _ ->
            tickerCacheService.saveAggregation(TickerAggregationTimeUnit.MINUTES, exchange, symbol, from, agg)
            val minuteAt = LocalDateTime.ofInstant(from, ZoneId.systemDefault())
            aggregationRepository.saveMinute(exchange, symbol, minuteAt, agg)
            log.info(
                "Aggregated ticker minute data: {}:{} at {} (high={}, low={}, count={})",
                exchange,
                symbol,
                minuteAt,
                agg.high,
                agg.low,
                agg.count,
            )
        },
        unit = ChronoUnit.MINUTES,
    )

    private fun hourJobFor(exchange: String, symbol: String) = AggregationJob<TickerAggregation>(
        reader = { from, to -> tickerCacheService.aggregateData(TickerAggregationTimeUnit.MINUTES, exchange, symbol, from, to) },
        writer = { agg, from, _ ->
            tickerCacheService.saveAggregation(TickerAggregationTimeUnit.HOURS, exchange, symbol, from, agg)
            val hourAt = LocalDateTime.ofInstant(from, ZoneId.systemDefault())
            aggregationRepository.saveHour(exchange, symbol, hourAt, agg)
            log.info(
                "Aggregated ticker hour data: {}:{} at {} (high={}, low={}, count={})",
                exchange,
                symbol,
                hourAt,
                agg.high,
                agg.low,
                agg.count,
            )
        },
        unit = ChronoUnit.HOURS,
    )

    private fun dayJobFor(exchange: String, symbol: String) = AggregationJob<TickerAggregation>(
        reader = { from, to -> tickerCacheService.aggregateData(TickerAggregationTimeUnit.HOURS, exchange, symbol, from, to) },
        writer = { agg, from, _ ->
            val dayAt = LocalDateTime.ofInstant(from, ZoneId.systemDefault()).toLocalDate()
            aggregationRepository.saveDay(exchange, symbol, dayAt, agg)
            log.info(
                "Aggregated ticker day data: {}:{} at {} (high={}, low={}, count={})",
                exchange,
                symbol,
                dayAt,
                agg.high,
                agg.low,
                agg.count,
            )
        },
        unit = ChronoUnit.DAYS,
    )

    @Scheduled(cron = "2 * * * * *")
    fun aggregateMinute() {
        jobExecutor.execute(MINUTE_CONFIG) { runForAllTargets { e, s -> minuteJobFor(e, s) } }
    }

    @Scheduled(cron = "7 0 * * * *")
    fun aggregateHour() {
        jobExecutor.execute(HOUR_CONFIG) { runForAllTargets { e, s -> hourJobFor(e, s) } }
    }

    @Scheduled(cron = "12 0 0 * * *")
    fun aggregateDay() {
        jobExecutor.execute(DAY_CONFIG) { runForAllTargets { e, s -> dayJobFor(e, s) } }
    }

    private fun runForAllTargets(jobFactory: (String, String) -> AggregationJob<TickerAggregation>): JobResult {
        var anySuccess = false
        for ((exchange, symbol) in TARGETS) {
            val result = jobFactory(exchange, symbol).run()
            if (result is JobResult.Success) anySuccess = true
            if (result is JobResult.Failure) return result
        }
        return if (anySuccess) JobResult.Success else JobResult.Skipped("no_data")
    }
}
