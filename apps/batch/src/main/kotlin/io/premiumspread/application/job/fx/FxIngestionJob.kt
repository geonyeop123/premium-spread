package io.premiumspread.application.job.fx

import io.premiumspread.application.common.JobConfig
import io.premiumspread.application.common.JobExecutor
import io.premiumspread.application.common.JobConfigProvider
import io.premiumspread.application.common.DefaultJobConfigProvider
import io.premiumspread.application.common.JobResult
import io.premiumspread.domain.batch.BatchMarketProvider
import io.premiumspread.domain.job.JobId
import io.premiumspread.domain.market.ExchangeRateProvider
import io.premiumspread.domain.market.FxRateCacheWritePort
import io.premiumspread.domain.market.FxRateWritePort
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class FxIngestionJob(
    private val exchangeRateProvider: ExchangeRateProvider,
    private val rateWriter: FxRateWritePort,
    private val cacheWriter: FxRateCacheWritePort,
    private val marketProvider: BatchMarketProvider,
    private val executor: JobExecutor,
    private val jobConfigs: JobConfigProvider = DefaultJobConfigProvider,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun run(): JobResult = executor.execute(jobConfigs.get(JobId.FX_INGESTION)) {
            try {
                val market = marketProvider.defaultMarket()
                val fxRate = exchangeRateProvider.fetch(market.fxBase, market.fxQuote)

                // DB-first ordering is intentional. A failed durable write must never publish a cache-only rate.
                rateWriter.save(fxRate)
                cacheWriter.save(fxRate)

                log.info("Fetched exchange rate - USD/KRW: {}", fxRate.rate)
                JobResult.Success
            } catch (exception: Exception) {
                log.error("Failed to fetch exchange rate", exception)
                JobResult.Failure(exception)
            }
        }
}
