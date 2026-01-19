package io.premiumspread.domain.ticker

import java.math.BigDecimal
import java.time.Instant

data class Ticker private constructor(
    val id: TickerId,
    val exchange: Exchange,
    val exchangeRegion: ExchangeRegion,
    val quote: Quote,
    val price: BigDecimal,
    val observedAt: Instant,
) {
    init {
        if (price <= BigDecimal.ZERO) {
            throw InvalidTickerException("Ticker price must be positive.")
        }
    }

    companion object {
        fun create(
            exchange: Exchange,
            quote: Quote,
            price: BigDecimal,
            observedAt: Instant,
            id: TickerId = TickerId.random(),
        ): Ticker {
            return Ticker(
                id = id,
                exchange = exchange,
                exchangeRegion = exchange.region,
                quote = quote,
                price = price,
                observedAt = observedAt,
            )
        }
    }
}
