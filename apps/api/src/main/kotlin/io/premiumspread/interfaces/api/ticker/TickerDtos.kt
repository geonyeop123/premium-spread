package io.premiumspread.interfaces.api.ticker

import io.premiumspread.domain.ticker.Ticker
import java.math.BigDecimal
import java.time.Instant

class TickerRequest private constructor() {
    data class Ingest(
        val exchange: String,
        val baseCode: String,
        val quoteCurrency: String,
        val price: BigDecimal,
        val observedAt: Instant,
    )
}

class TickerResponse private constructor() {
    data class Detail(
        val id: Long,
        val exchange: String,
        val exchangeRegion: String,
        val baseCode: String,
        val quoteCurrency: String,
        val price: BigDecimal,
        val observedAt: Instant,
    ) {
        companion object {
            fun from(ticker: Ticker): Detail = Detail(
                id = ticker.id,
                exchange = ticker.exchange.name,
                exchangeRegion = ticker.exchangeRegion.name,
                baseCode = ticker.quote.baseCode,
                quoteCurrency = ticker.quote.currency.name,
                price = ticker.price,
                observedAt = ticker.observedAt,
            )
        }
    }
}
