package io.premiumspread.application.job.aggregation

import io.premiumspread.application.common.JobConfig
import io.premiumspread.application.common.JobExecutor
import io.premiumspread.application.common.JobConfigProvider
import io.premiumspread.application.common.DefaultJobConfigProvider
import io.premiumspread.application.common.JobResult
import io.premiumspread.domain.batch.BatchMarketProvider
import io.premiumspread.domain.aggregation.AggregationUnit
import io.premiumspread.domain.aggregation.PremiumAggregatePort
import io.premiumspread.domain.aggregation.PremiumSummaryPeriod
import io.premiumspread.domain.job.JobId
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.temporal.ChronoUnit

/** Premium 집계의 window 계산과 read/write 조합을 소유하는 application job. */
@Component
class PremiumAggregationJob(
    private val aggregatePort: PremiumAggregatePort,
    private val marketProvider: BatchMarketProvider,
    private val executor: JobExecutor,
    private val clock: Clock,
    private val windowPolicy: AggregationWindowPolicy,
    private val jobConfigs: JobConfigProvider = DefaultJobConfigProvider,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun updateSummary(): JobResult = executor.execute(jobConfigs.get(JobId.PREMIUM_SUMMARY)) {
        val now = clock.instant()
        PremiumSummaryPeriod.entries.forEach { period ->
            runCatching {
                val window = windowPolicy.between(now.minus(period.duration), now)
                aggregatePort.calculateSummary(marketProvider.defaultMarket().pair, period, window)
                    ?.let { aggregatePort.saveSummary(period, it) }
            }.onFailure { log.error("Failed to update {} premium summary", period, it) }
        }
        JobResult.Success
    }

    fun aggregateMinute(): JobResult = aggregate(
        unit = AggregationUnit.MINUTE,
        sourceUnit = null,
        chronoUnit = ChronoUnit.MINUTES,
        config = jobConfigs.get(JobId.PREMIUM_AGGREGATION_MINUTE),
    )

    fun aggregateHour(): JobResult = aggregate(
        unit = AggregationUnit.HOUR,
        sourceUnit = AggregationUnit.MINUTE,
        chronoUnit = ChronoUnit.HOURS,
        config = jobConfigs.get(JobId.PREMIUM_AGGREGATION_HOUR),
    )

    fun aggregateDay(): JobResult = aggregate(
        unit = AggregationUnit.DAY,
        sourceUnit = AggregationUnit.HOUR,
        chronoUnit = ChronoUnit.DAYS,
        config = jobConfigs.get(JobId.PREMIUM_AGGREGATION_DAY),
    )

    private fun aggregate(
        unit: AggregationUnit,
        sourceUnit: AggregationUnit?,
        chronoUnit: ChronoUnit,
        config: JobConfig,
    ): JobResult = executor.execute(config) {
        try {
            val window = windowPolicy.previous(clock.instant(), chronoUnit)
            val snapshot = aggregatePort.aggregate(marketProvider.defaultMarket().pair, sourceUnit, window)
                ?: return@execute JobResult.Skipped("no_data")
            aggregatePort.save(unit, window, snapshot)
            JobResult.Success
        } catch (exception: Exception) {
            log.error("Premium {} aggregation failed", unit, exception)
            JobResult.Failure(exception)
        }
    }
}
