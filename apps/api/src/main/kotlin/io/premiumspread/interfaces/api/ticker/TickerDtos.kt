package io.premiumspread.interfaces.api.ticker

import io.premiumspread.application.ticker.TickerResult
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
            fun from(result: TickerResult.Detail): Detail = Detail(
                id = result.id,
                exchange = result.exchange.name,
                exchangeRegion = result.exchangeRegion.name,
                baseCode = result.baseCode,
                quoteCurrency = result.quoteCurrency.name,
                price = result.price,
                observedAt = result.observedAt,
            )
        }
    }
}
