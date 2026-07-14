package io.premiumspread.infrastructure.batch.exchange

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import io.premiumspread.domain.exchangerate.ExchangeRateSnapshot
import io.premiumspread.domain.market.ExchangeRateProvider
import io.premiumspread.domain.ticker.Currency
import io.premiumspread.domain.ticker.Exchange
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatusCode
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientRequestException
import reactor.core.Exceptions
import reactor.core.publisher.Mono
import reactor.util.retry.Retry
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeoutException

class ExchangeRateClient(
    private val exchangeRateWebClient: WebClient,
    private val meterRegistry: MeterRegistry,
    private val properties: ExchangeRateClientProperties,
) : ExchangeRateProvider {
    private val log = LoggerFactory.getLogger(javaClass)

    fun fetchConfiguredRate(): ExchangeRateSnapshot = fetch(properties.base, properties.quote)

    override fun fetch(base: Currency, quote: Currency): ExchangeRateSnapshot = fetch(base.code, quote.code)

    fun fetch(baseCurrency: String, quoteCurrency: String): ExchangeRateSnapshot {
        val base = baseCurrency.trim().uppercase()
        val quote = quoteCurrency.trim().uppercase()
        val sample = Timer.start(meterRegistry)

        return try {
            val response = fetchResponse(base, quote)
            response.toSnapshot(base, quote).also {
                meterRegistry.counter(FETCH_METRIC, "provider", PROVIDER, "outcome", "success").increment()
                log.debug("Fetched exchange rate: {}/{} = {}", base, quote, it.rate)
            }
        } catch (exception: Exception) {
            val cause = Exceptions.unwrap(exception)
            meterRegistry.counter(
                FETCH_METRIC,
                "provider",
                PROVIDER,
                "outcome",
                cause.toMetricOutcome(),
            ).increment()
            log.error("Failed to fetch exchange rate for {}/{}", base, quote, cause)
            throw cause
        } finally {
            sample.stop(
                Timer.builder("fx.fetch.latency")
                    .tag("provider", PROVIDER)
                    .register(meterRegistry),
            )
        }
    }

    private fun fetchResponse(base: String, quote: String): ExchangeRateResponse {
        val retry = Retry.backoff(properties.maxRetries.toLong(), properties.initialBackoff)
            .maxBackoff(properties.maxBackoff)
            .filter(::isRetryable)
            .doBeforeRetry { signal ->
                meterRegistry.counter("fx.fetch.retry", "provider", PROVIDER).increment()
                log.warn(
                    "Exchange rate request retry {}/{}: {}",
                    signal.totalRetries() + 1,
                    properties.maxRetries,
                    signal.failure().message,
                )
            }
            .onRetryExhaustedThrow { _, signal -> signal.failure() }

        return exchangeRateWebClient.get()
            .uri("/v6/{apiKey}/pair/{base}/{quote}", properties.apiKey, base, quote)
            .exchangeToMono { response -> response.toResponseMono() }
            .retryWhen(retry)
            .block(properties.overallDeadline())
            ?: throw ExchangeRateApiException("Exchange rate API returned an empty response")
    }

    private fun org.springframework.web.reactive.function.client.ClientResponse.toResponseMono(): Mono<ExchangeRateResponse> {
        if (statusCode().is2xxSuccessful) return bodyToMono(ExchangeRateResponse::class.java)

        return bodyToMono(String::class.java)
            .defaultIfEmpty("")
            .flatMap { body ->
                val status = statusCode()
                if (properties.isRetryable(status.value())) {
                    Mono.error(RetryableExchangeRateException(status, body))
                } else {
                    Mono.error(ExchangeRateHttpException(status, body))
                }
            }
    }

    private fun ExchangeRateResponse.toSnapshot(base: String, quote: String): ExchangeRateSnapshot {
        if (result != "success") {
            throw ExchangeRateApiException("Exchange rate API error: ${errorType ?: "unknown"}")
        }
        if (baseCode != null && !baseCode.equals(base, ignoreCase = true)) {
            throw ExchangeRateApiException("Unexpected base currency: $baseCode")
        }
        if (targetCode != null && !targetCode.equals(quote, ignoreCase = true)) {
            throw ExchangeRateApiException("Unexpected quote currency: $targetCode")
        }
        return ExchangeRateSnapshot(
            baseCurrency = base,
            quoteCurrency = quote,
            rate = conversionRate ?: throw ExchangeRateApiException("No conversion rate in response"),
            observedAt = timeLastUpdateUnix?.let(Instant::ofEpochSecond)
                ?: throw ExchangeRateApiException("No observation timestamp in response"),
            source = Exchange.FX_PROVIDER,
        )
    }

    private fun isRetryable(throwable: Throwable): Boolean {
        val cause = Exceptions.unwrap(throwable)
        return cause is RetryableExchangeRateException ||
            cause is WebClientRequestException ||
            cause is TimeoutException ||
            cause.cause is TimeoutException
    }

    private fun Throwable.toMetricOutcome(): String = when (this) {
        is RetryableExchangeRateException, is ExchangeRateHttpException -> "http"
        is WebClientRequestException -> "connection"
        is TimeoutException -> "timeout"
        is ExchangeRateApiException -> "contract"
        else -> "other"
    }

    private companion object {
        const val PROVIDER = "EXCHANGERATE_API"
        const val FETCH_METRIC = "fx.fetch"
    }
}

open class ExchangeRateApiException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

open class ExchangeRateHttpException(
    val status: HttpStatusCode,
    body: String,
) : ExchangeRateApiException("Exchange rate HTTP ${status.value()}: ${body.take(256)}")

class RetryableExchangeRateException(
    status: HttpStatusCode,
    body: String,
) : ExchangeRateHttpException(status, body)
