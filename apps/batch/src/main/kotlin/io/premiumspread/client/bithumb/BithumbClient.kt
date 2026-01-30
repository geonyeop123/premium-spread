package io.premiumspread.client.bithumb

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
class BithumbClient(
    private val bithumbWebClient: WebClient,
    private val meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val EXCHANGE = "BITHUMB"
        private const val MAX_RETRIES = 3
        private val RETRY_DELAY = Duration.ofMillis(500)
    }

    /**
     * BTC/KRW 현물 시세 조회
     *
     * Bithumb Public API는 API Key가 필요하지 않음
     * Rate Limit: 15 requests/second
     */
    suspend fun getBtcTicker(): TickerData {
        return getTicker("BTC", "KRW")
    }

    /**
     * 지정 코인/통화 시세 조회
     */
    suspend fun getTicker(symbol: String, currency: String): TickerData {
        val sample = Timer.start(meterRegistry)

        return try {
            val response = fetchWithRetry("${symbol}_$currency")

            if (response.status != "0000") {
                throw BithumbApiException("Bithumb API error: status=${response.status}, message=${response.message}")
            }

            val data = response.data
                ?: throw BithumbApiException("Bithumb API returned null data for $symbol/$currency")

            TickerData(
                exchange = EXCHANGE,
                symbol = symbol,
                currency = currency,
                price = data.closingPrice.toBigDecimalOrNull()
                    ?: throw BithumbApiException("Invalid price: ${data.closingPrice}"),
                volume = data.unitsTraded24H?.toBigDecimalOrNull(),
                timestamp = data.date?.let { Instant.ofEpochMilli(it.toLong()) } ?: Instant.now(),
            ).also {
                log.debug("Fetched Bithumb ticker: {} {} = {}", symbol, currency, it.price)
                meterRegistry.counter("ticker.fetch.success", "exchange", EXCHANGE).increment()
            }
        } catch (e: Exception) {
            meterRegistry.counter("ticker.fetch.error", "exchange", EXCHANGE, "error", e.javaClass.simpleName).increment()
            log.error("Failed to fetch Bithumb ticker for $symbol/$currency", e)
            throw e
        } finally {
            sample.stop(
                Timer.builder("ticker.fetch.latency")
                    .tag("exchange", EXCHANGE)
                    .register(meterRegistry),
            )
        }
    }

    private suspend fun fetchWithRetry(pair: String): BithumbTickerResponse {
        var lastException: Exception? = null

        repeat(MAX_RETRIES) { attempt ->
            try {
                return bithumbWebClient
                    .get()
                    .uri("/public/ticker/$pair")
                    .retrieve()
                    .awaitBody<BithumbTickerResponse>()
            } catch (e: Exception) {
                lastException = e
                log.warn("Bithumb API request failed (attempt ${attempt + 1}/$MAX_RETRIES): ${e.message}")
                if (attempt < MAX_RETRIES - 1) {
                    kotlinx.coroutines.delay(RETRY_DELAY.toMillis() * (attempt + 1))
                }
            }
        }

        throw lastException ?: BithumbApiException("Unknown error after $MAX_RETRIES retries")
    }
}

class BithumbApiException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
