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
 *   "symbol": "EUR/USD",
 *   "rate": 1.12167,
 *   "timestamp": 1792975320
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
)
