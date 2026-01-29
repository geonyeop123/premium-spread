package io.premiumspread.client

import java.math.BigDecimal
import java.time.Instant

/**
 * 외부 API에서 조회한 티커 데이터
 */
data class TickerData(
    val exchange: String,
    val symbol: String,
    val currency: String,
    val price: BigDecimal,
    val volume: BigDecimal?,
    val timestamp: Instant,
)

/**
 * 환율 데이터
 */
data class FxRateData(
    val baseCurrency: String,
    val quoteCurrency: String,
    val rate: BigDecimal,
    val timestamp: Instant,
)
