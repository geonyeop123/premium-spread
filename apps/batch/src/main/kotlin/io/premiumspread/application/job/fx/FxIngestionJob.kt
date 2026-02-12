package io.premiumspread.application.job.fx

import io.premiumspread.application.common.JobResult
import io.premiumspread.cache.FxCacheService
import io.premiumspread.client.exchangerate.ExchangeRateClient
import io.premiumspread.repository.ExchangeRateRepository
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class FxIngestionJob(
    private val exchangeRateClient: ExchangeRateClient,
    private val fxCacheService: FxCacheService,
    private val exchangeRateRepository: ExchangeRateRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun run(): JobResult {
        return try {
            val fxRate = runBlocking { exchangeRateClient.getUsdKrwRate() }

            fxCacheService.save(fxRate)

            exchangeRateRepository.save(
                baseCurrency = fxRate.baseCurrency,
                quoteCurrency = fxRate.quoteCurrency,
                rate = fxRate.rate,
                observedAt = fxRate.timestamp,
            )

            log.info("Fetched exchange rate - USD/KRW: {}", fxRate.rate)
            JobResult.Success
        } catch (e: Exception) {
            log.error("Failed to fetch exchange rate", e)
            JobResult.Failure(e)
        }
    }
}
