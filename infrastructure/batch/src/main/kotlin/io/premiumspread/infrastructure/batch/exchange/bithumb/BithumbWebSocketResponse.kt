package io.premiumspread.infrastructure.batch.exchange.bithumb

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

/**
 * 빗썸 WebSocket Public — ticker 채널 메시지.
 *
 * `type` 이 "ticker"가 아닌 메시지(예: 구독 응답)는 무시한다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class BithumbWebSocketTickerMessage(val type: String? = null, val content: BithumbWebSocketTickerContent? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class BithumbWebSocketTickerContent(
    val symbol: String, // 예: "BTC_KRW"
    val tickType: String? = null, // "24H" 등
    val date: String? = null, // "yyyyMMdd"
    val time: String? = null, // "HHmmss"
    val closePrice: String, // 종가 (현재가)
    val openPrice: String? = null,
    val lowPrice: String? = null,
    val highPrice: String? = null,
    val volume: String? = null,
    val chgRate: String? = null,
    val chgAmt: String? = null,
)
