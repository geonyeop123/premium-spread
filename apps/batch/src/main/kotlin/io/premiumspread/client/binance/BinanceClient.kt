package io.premiumspread.client.binance

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import io.premiumspread.client.TickerData
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBody
import java.time.Duration
import java.time.Instant

@Component
class BinanceClient(
    private val binanceWebClient: WebClient,
    private val meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val EXCHANGE = "BINANCE"
        private const val MAX_RETRIES = 3
        private val RETRY_DELAY = Duration.ofMillis(500)
    }

    /**
     * BTCUSDT 무기한 선물 시세 조회
     *
     * Binance Futures Public API - API Key 불필요 (시세 조회)
     * Rate Limit: 1200 requests/minute (20 req/s)
     */
    suspend fun getBtcFuturesTicker(): TickerData {
        return getFuturesTicker("BTCUSDT")
    }

    /**
     * 지정 심볼 선물 시세 조회
     */
    suspend fun getFuturesTicker(symbol: String): TickerData {
        val sample = Timer.start(meterRegistry)

        return try {
            val response = fetchPriceWithRetry(symbol)

            TickerData(
                exchange = EXCHANGE,
                symbol = extractBaseSymbol(symbol),
                currency = extractQuoteCurrency(symbol),
                price = response.price.toBigDecimalOrNull()
                    ?: throw BinanceApiException("Invalid price: ${response.price}"),
                volume = null,
                timestamp = response.time?.let { Instant.ofEpochMilli(it) } ?: Instant.now(),
            ).also {
                log.debug("Fetched Binance futures ticker: {} = {}", symbol, it.price)
                meterRegistry.counter("ticker.fetch.success", "exchange", EXCHANGE).increment()
            }
        } catch (e: Exception) {
            meterRegistry.counter("ticker.fetch.error", "exchange", EXCHANGE, "error", e.javaClass.simpleName).increment()
            log.error("Failed to fetch Binance futures ticker for $symbol", e)
            throw e
        } finally {
            sample.stop(
                Timer.builder("ticker.fetch.latency")
                    .tag("exchange", EXCHANGE)
                    .register(meterRegistry),
            )
        }
    }

    /**
     * 24시간 통계 포함 선물 시세 조회
     */
    suspend fun getFuturesTickerWithVolume(symbol: String): TickerData {
        val sample = Timer.start(meterRegistry)

        return try {
            val response = fetch24hrTickerWithRetry(symbol)

            TickerData(
                exchange = EXCHANGE,
                symbol = extractBaseSymbol(symbol),
                currency = extractQuoteCurrency(symbol),
                price = response.lastPrice.toBigDecimalOrNull()
                    ?: throw BinanceApiException("Invalid price: ${response.lastPrice}"),
                volume = response.volume?.toBigDecimalOrNull(),
                timestamp = response.closeTime?.let { Instant.ofEpochMilli(it) } ?: Instant.now(),
            ).also {
                log.debug("Fetched Binance futures ticker with volume: {} = {}, vol={}", symbol, it.price, it.volume)
                meterRegistry.counter("ticker.fetch.success", "exchange", EXCHANGE).increment()
            }
        } catch (e: Exception) {
            meterRegistry.counter("ticker.fetch.error", "exchange", EXCHANGE, "error", e.javaClass.simpleName).increment()
            log.error("Failed to fetch Binance futures 24hr ticker for $symbol", e)
            throw e
        } finally {
            sample.stop(
                Timer.builder("ticker.fetch.latency")
                    .tag("exchange", EXCHANGE)
                    .register(meterRegistry),
            )
        }
    }

    private suspend fun fetchPriceWithRetry(symbol: String): BinancePriceResponse {
        var lastException: Exception? = null

        repeat(MAX_RETRIES) { attempt ->
            try {
                return binanceWebClient
                    .get()
                    .uri("/fapi/v1/ticker/price?symbol=$symbol")
                    .retrieve()
                    .awaitBody<BinancePriceResponse>()
            } catch (e: Exception) {
                lastException = e
                log.warn("Binance API request failed (attempt ${attempt + 1}/$MAX_RETRIES): ${e.message}")
                if (attempt < MAX_RETRIES - 1) {
                    kotlinx.coroutines.delay(RETRY_DELAY.toMillis() * (attempt + 1))
                }
            }
        }

        throw lastException ?: BinanceApiException("Unknown error after $MAX_RETRIES retries")
    }

    private suspend fun fetch24hrTickerWithRetry(symbol: String): Binance24hrTickerResponse {
        var lastException: Exception? = null

        repeat(MAX_RETRIES) { attempt ->
            try {
                return binanceWebClient
                    .get()
                    .uri("/fapi/v1/ticker/24hr?symbol=$symbol")
                    .retrieve()
                    .awaitBody<Binance24hrTickerResponse>()
            } catch (e: Exception) {
                lastException = e
                log.warn("Binance 24hr API request failed (attempt ${attempt + 1}/$MAX_RETRIES): ${e.message}")
                if (attempt < MAX_RETRIES - 1) {
                    kotlinx.coroutines.delay(RETRY_DELAY.toMillis() * (attempt + 1))
                }
            }
        }

        throw lastException ?: BinanceApiException("Unknown error after $MAX_RETRIES retries")
    }

    private fun extractBaseSymbol(symbol: String): String {
        return when {
            symbol.endsWith("USDT") -> symbol.dropLast(4)
            symbol.endsWith("USD") -> symbol.dropLast(3)
            symbol.endsWith("BUSD") -> symbol.dropLast(4)
            else -> symbol
        }
    }

    private fun extractQuoteCurrency(symbol: String): String {
        return when {
            symbol.endsWith("USDT") -> "USDT"
            symbol.endsWith("USD") -> "USD"
            symbol.endsWith("BUSD") -> "BUSD"
            else -> "USD"
        }
    }
}

class BinanceApiException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
