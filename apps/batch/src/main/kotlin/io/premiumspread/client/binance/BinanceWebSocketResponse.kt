package io.premiumspread.client.binance

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Binance Futures @miniTicker stream payload.
 *
 * URL: wss://fstream.binance.com/ws/{symbol}@miniTicker
 *
 * Push 주기: 1초 고정 (마지막 1초 동안의 close/open/high/low/volume).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class BinanceMiniTickerMessage(
    @JsonProperty("e") val eventType: String,
    @JsonProperty("E") val eventTime: Long,
    @JsonProperty("s") val symbol: String,
    @JsonProperty("c") val close: String,
    @JsonProperty("o") val open: String? = null,
    @JsonProperty("h") val high: String? = null,
    @JsonProperty("l") val low: String? = null,
    @JsonProperty("v") val volume: String? = null,
    @JsonProperty("q") val quoteVolume: String? = null,
)
