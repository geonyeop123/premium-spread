package io.premiumspread.client

import io.premiumspread.domain.exchangerate.ExchangeRateSnapshot
import io.premiumspread.domain.ticker.Exchange
import io.premiumspread.domain.ticker.TickerSnapshot
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
) {
    fun toDomainSnapshot(): TickerSnapshot = TickerSnapshot(
        exchange = exchange,
        symbol = symbol,
        currency = currency,
        price = price,
        volume = volume,
        observedAt = timestamp,
    )
}

/**
 * 환율 데이터
 */
data class FxRateData(
    val baseCurrency: String,
    val quoteCurrency: String,
    val rate: BigDecimal,
    val timestamp: Instant,
    val source: Exchange = Exchange.FX_PROVIDER,
) {
    fun toDomainSnapshot(): ExchangeRateSnapshot = ExchangeRateSnapshot(
        baseCurrency = baseCurrency,
        quoteCurrency = quoteCurrency,
        rate = rate,
        observedAt = timestamp,
        source = source,
    )
}
