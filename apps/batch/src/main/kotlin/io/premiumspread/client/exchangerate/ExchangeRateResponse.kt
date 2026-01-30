package io.premiumspread.client.exchangerate

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import java.math.BigDecimal

/**
 * ExchangeRate-API Response (Pair Endpoint)
 *
 * GET /v6/{api-key}/pair/{base}/{quote}
 *
 * Example (Success):
 * {
 *   "result": "success",
 *   "documentation": "https://www.exchangerate-api.com/docs",
 *   "terms_of_use": "https://www.exchangerate-api.com/terms",
 *   "time_last_update_unix": 1706486401,
 *   "time_last_update_utc": "Mon, 29 Jan 2024 00:00:01 +0000",
 *   "time_next_update_unix": 1706572801,
 *   "time_next_update_utc": "Tue, 30 Jan 2024 00:00:01 +0000",
 *   "base_code": "USD",
 *   "target_code": "KRW",
 *   "conversion_rate": 1332.6
 * }
 *
 * Example (Error):
 * {
 *   "result": "error",
 *   "documentation": "https://www.exchangerate-api.com/docs",
 *   "terms-of-use": "https://www.exchangerate-api.com/terms",
 *   "error-type": "unsupported-code"
 * }
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ExchangeRateResponse(
    val result: String,

    @JsonProperty("time_last_update_unix")
    val timeLastUpdateUnix: Long? = null,

    @JsonProperty("time_last_update_utc")
    val timeLastUpdateUtc: String? = null,

    @JsonProperty("time_next_update_unix")
    val timeNextUpdateUnix: Long? = null,

    @JsonProperty("time_next_update_utc")
    val timeNextUpdateUtc: String? = null,

    @JsonProperty("base_code")
    val baseCode: String? = null,

    @JsonProperty("target_code")
    val targetCode: String? = null,

    @JsonProperty("conversion_rate")
    val conversionRate: BigDecimal? = null,

    @JsonProperty("error-type")
    val errorType: String? = null,
)
