package io.premiumspread.application.job.ticker

import io.premiumspread.application.common.JobResult
import io.premiumspread.cache.TickerCacheService
import io.premiumspread.client.binance.BinanceClient
import io.premiumspread.client.bithumb.BithumbClient
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class TickerIngestionJob(
    private val bithumbClient: BithumbClient,
    private val binanceClient: BinanceClient,
    private val tickerCacheService: TickerCacheService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun run(): JobResult {
        return try {
            runBlocking {
                val bithumbDeferred = async { bithumbClient.getBtcTicker() }
                val binanceDeferred = async { binanceClient.getBtcFuturesTicker() }

                val bithumbTicker = bithumbDeferred.await()
                val binanceTicker = binanceDeferred.await()

                tickerCacheService.saveAll(bithumbTicker, binanceTicker)
                tickerCacheService.saveToSeconds(bithumbTicker)
                tickerCacheService.saveToSeconds(binanceTicker)

                log.debug(
                    "Fetched tickers - Bithumb: {} KRW, Binance: {} USDT",
                    bithumbTicker.price,
                    binanceTicker.price,
                )
            }
            JobResult.Success
        } catch (e: Exception) {
            log.error("Failed to fetch tickers", e)
            JobResult.Failure(e)
        }
    }
}
