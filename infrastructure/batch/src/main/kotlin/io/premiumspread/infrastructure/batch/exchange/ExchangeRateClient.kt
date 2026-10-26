package io.premiumspread.infrastructure.batch.exchange

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import io.premiumspread.domain.exchangerate.ExchangeRateSnapshot
import io.premiumspread.domain.market.ExchangeRateProvider
import io.premiumspread.domain.ticker.Currency
import io.premiumspread.domain.ticker.Exchange
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
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
                log.debug(
                    "Fetched exchange rate base={} quote={} rate={} observedAt={}",
                    base,
                    quote,
                    it.rate,
                    it.observedAt,
                )
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
                    "Exchange rate request retry {}/{}: {}: {}",
                    signal.totalRetries() + 1,
                    properties.maxRetries,
                    signal.failure().javaClass.simpleName,
                    signal.failure().message,
                )
            }
            .onRetryExhaustedThrow { _, signal -> signal.failure() }

        return exchangeRateWebClient.get()
            .uri { it.path("/exchange_rate").queryParam("symbol", "{symbol}").build("$base/$quote") }
            .header(HttpHeaders.AUTHORIZATION, "apikey ${properties.apiKey}")
            .exchangeToMono { response ->
                // Start the body deadline after response headers arrive. Applying timeout
                // around the whole exchange also measures one-time Netty initialization and
                // can fail before a request reaches the server on a cold JVM.
                response.toResponseMono().timeout(properties.readTimeout)
            }
            .retryWhen(retry)
            .block(properties.overallDeadline())
            ?: fail("Exchange rate API returned an empty response")
    }

    private fun org.springframework.web.reactive.function.client.ClientResponse.toResponseMono(): Mono<ExchangeRateResponse> {
        if (statusCode().is2xxSuccessful) return bodyToMono(ExchangeRateResponse::class.java)

        // 오류 본문은 소비·폐기하고 예외 메시지와 로그에 넣지 않는다.
        val status = statusCode()
        val error = if (properties.isRetryable(status.value())) {
            RetryableExchangeRateException(status)
        } else {
            ExchangeRateHttpException(status)
        }
        return releaseBody().then(Mono.error(error))
    }

    private fun ExchangeRateResponse.toSnapshot(base: String, quote: String): ExchangeRateSnapshot {
        if (status.equals("error", ignoreCase = true)) fail("Exchange rate API error: code=${code ?: "unknown"}")
        if (symbol == null || !symbol.equals("$base/$quote", ignoreCase = true)) {
            fail("Unexpected exchange rate symbol (expected $base/$quote)")
        }
        val observedRate = rate ?: fail("No rate in response")
        if (observedRate.signum() <= 0) fail("Non-positive rate in response")
        val epochSeconds = timestamp?.takeIf { it > 0 } ?: fail("No observation timestamp in response")
        return ExchangeRateSnapshot(
            baseCurrency = base,
            quoteCurrency = quote,
            rate = observedRate,
            observedAt = Instant.ofEpochSecond(epochSeconds),
            source = Exchange.FX_PROVIDER,
        )
    }

    private fun fail(message: String): Nothing = throw ExchangeRateApiException(message)

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
        const val PROVIDER = "TWELVE_DATA"
        const val FETCH_METRIC = "fx.fetch"
    }
}

open class ExchangeRateApiException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

// 응답 본문은 어떤 HTTP 예외 메시지에도 넣지 않는다.
open class ExchangeRateHttpException(val status: HttpStatusCode) :
    ExchangeRateApiException("Exchange rate HTTP ${status.value()}")

class RetryableExchangeRateException(status: HttpStatusCode) : ExchangeRateHttpException(status)
