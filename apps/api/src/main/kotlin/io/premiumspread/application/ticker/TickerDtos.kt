package io.premiumspread.application.ticker

import io.premiumspread.domain.ticker.Currency
import io.premiumspread.domain.ticker.Exchange
import io.premiumspread.domain.ticker.ExchangeRegion
import io.premiumspread.domain.ticker.Ticker
import java.math.BigDecimal
import java.time.Instant

class TickerCriteria private constructor() {
    data class Ingest(
        val exchange: Exchange,
        val baseCode: String,
        val quoteCurrency: Currency,
        val price: BigDecimal,
        val observedAt: Instant,
    )
}

class TickerResult private constructor() {
    data class Detail(
        val id: Long,
        val exchange: Exchange,
        val exchangeRegion: ExchangeRegion,
        val baseCode: String,
        val quoteCurrency: Currency,
        val price: BigDecimal,
        val observedAt: Instant,
    ) {
        companion object {
            fun from(ticker: Ticker): Detail = Detail(
                id = ticker.id,
                exchange = ticker.exchange,
                exchangeRegion = ticker.exchangeRegion,
                baseCode = ticker.quote.baseCode,
                quoteCurrency = ticker.quote.currency,
                price = ticker.price,
                observedAt = ticker.observedAt,
            )
        }
    }
}
