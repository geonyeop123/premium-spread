package io.premiumspread.infrastructure.batch.exchange

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import java.math.BigDecimal

/**
 * Twelve Data exchange_rate Response
 *
 * GET /exchange_rate?symbol={BASE}/{QUOTE}  (Authorization: apikey {key})
 *
 * Example (Success):
 * {
 *   "symbol": "USD/KRW",
 *   "rate": 1234.5678,
 *   "timestamp": 1767225600
 * }
 *
 * Example (Error):
 * {
 *   "code": 401,
 *   "message": "provider message",
 *   "status": "error"
 * }
 *
 * 이 객체는 로그와 예외 메시지에 넣지 않는다(제공자 message 노출 방지).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ExchangeRateResponse(
    val symbol: String? = null,
    val rate: BigDecimal? = null,
    val timestamp: Long? = null,
    val status: String? = null,
    val code: Int? = null,
    val message: String? = null,
) {
    // 제공자 message 는 자동 toString 으로 로그에 새지 않게 가린다.
    override fun toString(): String =
        "ExchangeRateResponse(symbol=$symbol, rate=$rate, timestamp=$timestamp, " +
            "status=$status, code=$code, message=<redacted>)"
}
