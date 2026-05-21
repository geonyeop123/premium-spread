package io.premiumspread.client.binance

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Binance USD-M Futures @bookTicker stream payload.
 *
 * URL: wss://fstream.binance.com/public/ws/{symbol}@bookTicker
 *
 * Push 주기: best bid/ask 변동 시마다 실시간 push (스냅샷이 아닌 변경 이벤트).
 * 가격은 best bid/ask의 mid `(b + a) / 2`로 산정한다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class BinanceBookTickerMessage(
    @JsonProperty("E") val eventTime: Long,
    @JsonProperty("s") val symbol: String,
    @JsonProperty("b") val bestBid: String,
    @JsonProperty("a") val bestAsk: String,
    @JsonProperty("e") val eventType: String? = null,
    @JsonProperty("u") val updateId: Long? = null,
    @JsonProperty("T") val transactTime: Long? = null,
    @JsonProperty("B") val bidQty: String? = null,
    @JsonProperty("A") val askQty: String? = null,
)
