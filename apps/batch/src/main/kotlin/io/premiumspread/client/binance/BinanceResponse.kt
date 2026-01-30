package io.premiumspread.client.binance

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

/**
 * Binance Futures Price Ticker Response
 *
 * GET /fapi/v1/ticker/price
 *
 * Example:
 * {
 *   "symbol": "BTCUSDT",
 *   "price": "89277.10",
 *   "time": 1706500000000
 * }
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class BinancePriceResponse(
    val symbol: String,
    val price: String,
    val time: Long? = null,
)

/**
 * Binance Futures 24hr Ticker Response
 *
 * GET /fapi/v1/ticker/24hr
 *
 * Example:
 * {
 *   "symbol": "BTCUSDT",
 *   "priceChange": "1234.50",
 *   "priceChangePercent": "1.40",
 *   "weightedAvgPrice": "88500.12",
 *   "lastPrice": "89277.10",
 *   "lastQty": "0.123",
 *   "openPrice": "88042.60",
 *   "highPrice": "90000.00",
 *   "lowPrice": "87500.00",
 *   "volume": "123456.789",
 *   "quoteVolume": "10987654321.12",
 *   "openTime": 1706413600000,
 *   "closeTime": 1706500000000,
 *   "firstId": 123456789,
 *   "lastId": 123459999,
 *   "count": 3210
 * }
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class Binance24hrTickerResponse(
    val symbol: String,
    val priceChange: String? = null,
    val priceChangePercent: String? = null,
    val weightedAvgPrice: String? = null,
    val lastPrice: String,
    val lastQty: String? = null,
    val openPrice: String? = null,
    val highPrice: String? = null,
    val lowPrice: String? = null,
    val volume: String? = null,
    val quoteVolume: String? = null,
    val openTime: Long? = null,
    val closeTime: Long? = null,
    val firstId: Long? = null,
    val lastId: Long? = null,
    val count: Long? = null,
)
