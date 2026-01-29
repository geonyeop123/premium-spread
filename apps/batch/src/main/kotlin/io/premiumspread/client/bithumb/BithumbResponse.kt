package io.premiumspread.client.bithumb

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Bithumb Public Ticker API Response
 *
 * Example:
 * {
 *   "status": "0000",
 *   "data": {
 *     "opening_price": "129000000",
 *     "closing_price": "129555000",
 *     "min_price": "128000000",
 *     "max_price": "130000000",
 *     "units_traded": "1234.5678",
 *     "acc_trade_value": "159876543210",
 *     "prev_closing_price": "128500000",
 *     "units_traded_24H": "2345.6789",
 *     "acc_trade_value_24H": "302468135790",
 *     "fluctate_24H": "1055000",
 *     "fluctate_rate_24H": "0.82",
 *     "date": "1706500000000"
 *   }
 * }
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class BithumbTickerResponse(
    val status: String,
    val message: String? = null,
    val data: BithumbTickerData?,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class BithumbTickerData(
    @JsonProperty("opening_price")
    val openingPrice: String,

    @JsonProperty("closing_price")
    val closingPrice: String,

    @JsonProperty("min_price")
    val minPrice: String,

    @JsonProperty("max_price")
    val maxPrice: String,

    @JsonProperty("units_traded")
    val unitsTraded: String? = null,

    @JsonProperty("acc_trade_value")
    val accTradeValue: String? = null,

    @JsonProperty("prev_closing_price")
    val prevClosingPrice: String? = null,

    @JsonProperty("units_traded_24H")
    val unitsTraded24H: String? = null,

    @JsonProperty("acc_trade_value_24H")
    val accTradeValue24H: String? = null,

    @JsonProperty("fluctate_24H")
    val fluctate24H: String? = null,

    @JsonProperty("fluctate_rate_24H")
    val fluctateRate24H: String? = null,

    val date: String? = null,
)
