package io.premiumspread.client.exchangerate

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import io.premiumspread.client.FxRateData
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBody
import java.time.Duration
import java.time.Instant

@Component
class ExchangeRateClient(
    private val exchangeRateWebClient: WebClient,
    private val meterRegistry: MeterRegistry,
    @Value("\${exchange-rate.api-key:}") private val apiKey: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val PROVIDER = "EXCHANGERATE_API"
        private const val MAX_RETRIES = 3
        private val RETRY_DELAY = Duration.ofMillis(1000)
    }

    /**
     * USD/KRW 환율 조회
     *
     * ExchangeRate-API 사용 (https://www.exchangerate-api.com/)
     * Rate Limit: 1500 requests/month (무료), 30000/month (유료)
     */
    suspend fun getUsdKrwRate(): FxRateData {
        return getExchangeRate("USD", "KRW")
    }

    /**
     * 지정 통화쌍 환율 조회
     */
    suspend fun getExchangeRate(baseCurrency: String, quoteCurrency: String): FxRateData {
        val sample = Timer.start(meterRegistry)

        return try {
            val response = fetchWithRetry(baseCurrency, quoteCurrency)

            if (response.result != "success") {
                val errorMessage = "Exchange rate API error: ${response.errorType ?: "Unknown error"}"
                throw ExchangeRateApiException(errorMessage)
            }

            FxRateData(
                baseCurrency = baseCurrency,
                quoteCurrency = quoteCurrency,
                rate = response.conversionRate
                    ?: throw ExchangeRateApiException("No conversion rate in response"),
                timestamp = response.timeLastUpdateUnix
                    ?.let { Instant.ofEpochSecond(it) }
                    ?: Instant.now(),
            ).also {
                log.debug("Fetched exchange rate: {}/{} = {}", baseCurrency, quoteCurrency, it.rate)
                meterRegistry.counter("fx.fetch.success", "provider", PROVIDER).increment()
            }
        } catch (e: Exception) {
            meterRegistry.counter("fx.fetch.error", "provider", PROVIDER, "error", e.javaClass.simpleName).increment()
            log.error("Failed to fetch exchange rate for $baseCurrency/$quoteCurrency", e)
            throw e
        } finally {
            sample.stop(
                Timer.builder("fx.fetch.latency")
                    .tag("provider", PROVIDER)
                    .register(meterRegistry),
            )
        }
    }

    private suspend fun fetchWithRetry(baseCurrency: String, quoteCurrency: String): ExchangeRateResponse {
        var lastException: Exception? = null

        repeat(MAX_RETRIES) { attempt ->
            try {
                val uri = if (apiKey.isNotBlank()) {
                    "/v6/$apiKey/pair/$baseCurrency/$quoteCurrency"
                } else {
                    // Fallback to open exchange rates or similar free service
                    throw ExchangeRateApiException("API key is required for ExchangeRate API")
                }

                return exchangeRateWebClient
                    .get()
                    .uri(uri)
                    .retrieve()
                    .awaitBody<ExchangeRateResponse>()
            } catch (e: Exception) {
                lastException = e
                log.warn("Exchange rate API request failed (attempt ${attempt + 1}/$MAX_RETRIES): ${e.message}")
                if (attempt < MAX_RETRIES - 1) {
                    kotlinx.coroutines.delay(RETRY_DELAY.toMillis() * (attempt + 1))
                }
            }
        }

        throw lastException ?: ExchangeRateApiException("Unknown error after $MAX_RETRIES retries")
    }
}

class ExchangeRateApiException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
