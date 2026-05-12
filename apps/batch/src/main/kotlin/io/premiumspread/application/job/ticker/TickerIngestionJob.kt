package io.premiumspread.application.job.ticker

import io.premiumspread.application.common.JobResult
import io.premiumspread.cache.TickerCacheService
import io.premiumspread.client.binance.BinanceClient
import io.premiumspread.client.bithumb.BithumbClient
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
class TickerIngestionJob(
    private val bithumbClient: BithumbClient,
    private val binanceClient: BinanceClient,
    private val tickerCacheService: TickerCacheService,
    @Value("\${premium.ingestion.binance.mode:rest}") private val binanceMode: String,
    @Value("\${premium.ingestion.bithumb.mode:rest}") private val bithumbMode: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun run(): JobResult {
        return try {
            runBlocking {
                val bithumbDeferred = if (bithumbMode == REST_MODE) async { bithumbClient.getBtcTicker() } else null
                val binanceDeferred = if (binanceMode == REST_MODE) async { binanceClient.getBtcFuturesTicker() } else null

                bithumbDeferred?.await()?.let { ticker ->
                    tickerCacheService.save(ticker)
                    tickerCacheService.saveToSeconds(ticker)
                    log.debug("REST fetched Bithumb ticker: {} KRW", ticker.price)
                }
                binanceDeferred?.await()?.let { ticker ->
                    tickerCacheService.save(ticker)
                    tickerCacheService.saveToSeconds(ticker)
                    log.debug("REST fetched Binance ticker: {} USDT", ticker.price)
                }
            }
            JobResult.Success
        } catch (e: Exception) {
            log.error("Failed to fetch tickers", e)
            JobResult.Failure(e)
        }
    }

    companion object {
        private const val REST_MODE = "rest"
    }
}
