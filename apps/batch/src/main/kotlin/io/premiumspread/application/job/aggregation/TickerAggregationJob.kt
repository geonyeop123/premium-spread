package io.premiumspread.application.job.aggregation

import io.premiumspread.application.common.JobConfig
import io.premiumspread.application.common.JobExecutor
import io.premiumspread.application.common.JobConfigProvider
import io.premiumspread.application.common.DefaultJobConfigProvider
import io.premiumspread.application.common.JobResult
import io.premiumspread.domain.batch.BatchMarketProvider
import io.premiumspread.domain.aggregation.AggregationUnit
import io.premiumspread.domain.aggregation.TickerAggregatePort
import io.premiumspread.domain.job.JobId
import io.premiumspread.domain.ticker.Exchange
import io.premiumspread.domain.ticker.Quote
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.temporal.ChronoUnit

/** 모든 configured market target의 ticker 집계를 실행하는 application job. */
@Component
class TickerAggregationJob(
    private val aggregatePort: TickerAggregatePort,
    private val marketProvider: BatchMarketProvider,
    private val executor: JobExecutor,
    private val clock: Clock,
    private val windowPolicy: AggregationWindowPolicy,
    private val jobConfigs: JobConfigProvider = DefaultJobConfigProvider,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun aggregateMinute(): JobResult = aggregate(
        unit = AggregationUnit.MINUTE,
        sourceUnit = null,
        chronoUnit = ChronoUnit.MINUTES,
        config = jobConfigs.get(JobId.TICKER_AGGREGATION_MINUTE),
    )

    fun aggregateHour(): JobResult = aggregate(
        unit = AggregationUnit.HOUR,
        sourceUnit = AggregationUnit.MINUTE,
        chronoUnit = ChronoUnit.HOURS,
        config = jobConfigs.get(JobId.TICKER_AGGREGATION_HOUR),
    )

    fun aggregateDay(): JobResult = aggregate(
        unit = AggregationUnit.DAY,
        sourceUnit = AggregationUnit.HOUR,
        chronoUnit = ChronoUnit.DAYS,
        config = jobConfigs.get(JobId.TICKER_AGGREGATION_DAY),
    )

    private fun aggregate(
        unit: AggregationUnit,
        sourceUnit: AggregationUnit?,
        chronoUnit: ChronoUnit,
        config: JobConfig,
    ): JobResult = executor.execute(config) {
        val window = windowPolicy.previous(clock.instant(), chronoUnit)
        var anySuccess = false
        val market = marketProvider.defaultMarket()
        val targets = listOf(
            Target(market.pair.koreaExchange, market.koreaQuote),
            Target(market.pair.foreignExchange, market.foreignQuote),
        )
        for (target in targets) {
            val result = runCatching {
                aggregatePort.aggregate(target.exchange, target.quote, sourceUnit, window)?.also { snapshot ->
                    aggregatePort.save(unit, window, snapshot)
                }
            }.getOrElse { exception ->
                log.error("Ticker {} aggregation failed for {} {}", unit, target.exchange, target.quote, exception)
                return@execute JobResult.Failure(exception as? Exception ?: RuntimeException(exception))
            }
            anySuccess = anySuccess || result != null
        }
        if (anySuccess) JobResult.Success else JobResult.Skipped("no_data")
    }

    private data class Target(val exchange: Exchange, val quote: Quote)

}
