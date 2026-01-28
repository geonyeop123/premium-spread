package io.premiumspread.domain.ticker

import io.premiumspread.domain.BaseEntity
import io.premiumspread.domain.InvalidTickerException
import jakarta.persistence.Embedded
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant

@Entity
@Table(name = "ticker")
data class Ticker private constructor(
    val exchange: Exchange,
    val exchangeRegion: ExchangeRegion,
    @Embedded
    val quote: Quote,
    val price: BigDecimal,
    val observedAt: Instant,
) : BaseEntity() {
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
        ): Ticker {
            return Ticker(
                exchange = exchange,
                exchangeRegion = exchange.region,
                quote = quote,
                price = price,
                observedAt = observedAt,
            )
        }
    }
}
