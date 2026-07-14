package io.premiumspread.application.job.ticker

import io.premiumspread.application.common.JobConfig
import io.premiumspread.application.common.JobExecutor
import io.premiumspread.application.common.JobConfigProvider
import io.premiumspread.application.common.DefaultJobConfigProvider
import io.premiumspread.application.common.JobResult
import io.premiumspread.domain.batch.BatchMarketProvider
import io.premiumspread.domain.job.JobId
import io.premiumspread.domain.market.LatestMarketTickReadPort
import io.premiumspread.domain.market.TickerTimeSeriesWritePort
import io.premiumspread.domain.market.TickerFlushObserver
import io.premiumspread.domain.ticker.Exchange
import io.premiumspread.domain.ticker.Quote
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration

abstract class TickerFlushJob(
    private val exchange: Exchange,
    private val quote: Quote,
    private val config: JobConfig,
    private val latestTickReader: LatestMarketTickReadPort,
    private val timeSeriesWriter: TickerTimeSeriesWritePort,
    private val executor: JobExecutor,
    private val clock: Clock,
    private val observer: TickerFlushObserver,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun run(): JobResult = executor.execute(config) {
        try {
            val tick = latestTickReader.findLatest(exchange, quote)
                ?: return@execute JobResult.Skipped("no_data")
            if (Duration.between(tick.observedAt, clock.instant()) > MAX_TICK_AGE) {
                log.warn("Skipping stale ticker flush for {} {}", exchange, quote)
                observer.stale(exchange)
                return@execute JobResult.Skipped("stale_data")
            }
            timeSeriesWriter.saveSecond(tick, clock.instant())
            observer.succeeded(exchange)
            JobResult.Success
        } catch (exception: Exception) {
            log.error("Ticker flush failed for {} {}", exchange, quote, exception)
            observer.failed(exchange, exception)
            JobResult.Failure(exception)
        }
    }

    companion object {
        private val MAX_TICK_AGE = Duration.ofSeconds(10)
    }
}

@Component
class BinanceTickerFlushJob(
    marketProvider: BatchMarketProvider,
    latestTickReader: LatestMarketTickReadPort,
    timeSeriesWriter: TickerTimeSeriesWritePort,
    executor: JobExecutor,
    clock: Clock,
    jobConfigs: JobConfigProvider = DefaultJobConfigProvider,
    observer: TickerFlushObserver = TickerFlushObserver.NONE,
) : TickerFlushJob(
    exchange = marketProvider.defaultMarket().pair.foreignExchange,
    quote = marketProvider.defaultMarket().foreignQuote,
    config = jobConfigs.get(JobId.BINANCE_TICKER_FLUSH),
    latestTickReader = latestTickReader,
    timeSeriesWriter = timeSeriesWriter,
    executor = executor,
    clock = clock,
    observer = observer,
)

@Component
class BithumbTickerFlushJob(
    marketProvider: BatchMarketProvider,
    latestTickReader: LatestMarketTickReadPort,
    timeSeriesWriter: TickerTimeSeriesWritePort,
    executor: JobExecutor,
    clock: Clock,
    jobConfigs: JobConfigProvider = DefaultJobConfigProvider,
    observer: TickerFlushObserver = TickerFlushObserver.NONE,
) : TickerFlushJob(
    exchange = marketProvider.defaultMarket().pair.koreaExchange,
    quote = marketProvider.defaultMarket().koreaQuote,
    config = jobConfigs.get(JobId.BITHUMB_TICKER_FLUSH),
    latestTickReader = latestTickReader,
    timeSeriesWriter = timeSeriesWriter,
    executor = executor,
    clock = clock,
    observer = observer,
)
