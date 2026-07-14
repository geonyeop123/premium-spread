package io.premiumspread.scheduler

import io.premiumspread.application.common.JobConfig
import io.premiumspread.application.common.JobExecutor
import io.premiumspread.application.common.JobResult
import io.premiumspread.application.job.aggregation.AggregationJob
import io.premiumspread.application.job.aggregation.AggregationWindowPolicy
import io.premiumspread.cache.TickerCacheService
import io.premiumspread.redis.TickerAggregationTimeUnit
import io.premiumspread.repository.TickerAggregation
import io.premiumspread.repository.TickerAggregationRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

@Component
class TickerAggregationScheduler(
    private val tickerCacheService: TickerCacheService,
    private val aggregationRepository: TickerAggregationRepository,
    private val jobExecutor: JobExecutor,
    private val clock: Clock,
    private val windowPolicy: AggregationWindowPolicy,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val LOCK_TICKER_AGGREGATION = "lock:ticker:aggregation"

        private data class AggregationTarget(val exchange: String, val symbol: String, val currency: String)

        private val TARGETS = listOf(
            AggregationTarget("bithumb", "btc", "KRW"),
            AggregationTarget("binance", "btc", "USD"),
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

    private fun minuteJobFor(exchange: String, symbol: String, currency: String) = AggregationJob<TickerAggregation>(
        reader = { from, to -> tickerCacheService.aggregateSecondsData(exchange, symbol, currency, from, to) },
        writer = { agg, from, _ ->
            tickerCacheService.saveAggregation(TickerAggregationTimeUnit.MINUTES, exchange, symbol, from, agg)
            aggregationRepository.saveMinute(exchange, symbol, from, agg)
            log.info(
                "Aggregated ticker minute data: {}:{} at {} (high={}, low={}, count={})",
                exchange,
                symbol,
                from,
                agg.high,
                agg.low,
                agg.count,
            )
        },
        unit = ChronoUnit.MINUTES,
        clock = clock,
        windowPolicy = windowPolicy,
    )

    private fun hourJobFor(exchange: String, symbol: String, currency: String) = AggregationJob<TickerAggregation>(
        reader = { from, to -> tickerCacheService.aggregateData(TickerAggregationTimeUnit.MINUTES, exchange, symbol, currency, from, to) },
        writer = { agg, from, _ ->
            tickerCacheService.saveAggregation(TickerAggregationTimeUnit.HOURS, exchange, symbol, from, agg)
            aggregationRepository.saveHour(exchange, symbol, from, agg)
            log.info(
                "Aggregated ticker hour data: {}:{} at {} (high={}, low={}, count={})",
                exchange,
                symbol,
                from,
                agg.high,
                agg.low,
                agg.count,
            )
        },
        unit = ChronoUnit.HOURS,
        clock = clock,
        windowPolicy = windowPolicy,
    )

    private fun dayJobFor(exchange: String, symbol: String, currency: String) = AggregationJob<TickerAggregation>(
        reader = { from, to -> tickerCacheService.aggregateData(TickerAggregationTimeUnit.HOURS, exchange, symbol, currency, from, to) },
        writer = { agg, from, _ ->
            val dayAt = from.atZone(windowPolicy.zoneId).toLocalDate()
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
        clock = clock,
        windowPolicy = windowPolicy,
    )

    @Scheduled(cron = "2 * * * * *")
    fun aggregateMinute() {
        jobExecutor.execute(MINUTE_CONFIG) { runForAllTargets { e, s, c -> minuteJobFor(e, s, c) } }
    }

    @Scheduled(cron = "7 0 * * * *")
    fun aggregateHour() {
        jobExecutor.execute(HOUR_CONFIG) { runForAllTargets { e, s, c -> hourJobFor(e, s, c) } }
    }

    @Scheduled(cron = "12 0 0 * * *", zone = "\${aggregation.zone:Asia/Seoul}")
    fun aggregateDay() {
        jobExecutor.execute(DAY_CONFIG) { runForAllTargets { e, s, c -> dayJobFor(e, s, c) } }
    }

    private fun runForAllTargets(jobFactory: (String, String, String) -> AggregationJob<TickerAggregation>): JobResult {
        var anySuccess = false
        for (target in TARGETS) {
            val result = jobFactory(target.exchange, target.symbol, target.currency).run()
            if (result is JobResult.Success) anySuccess = true
            if (result is JobResult.Failure) return result
        }
        return if (anySuccess) JobResult.Success else JobResult.Skipped("no_data")
    }
}
