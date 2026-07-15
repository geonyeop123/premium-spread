package io.premiumspread.interfaces.api.ticker

import io.premiumspread.application.ticker.TickerResult
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import java.math.BigDecimal
import java.time.Instant

class TickerRequest private constructor() {
    data class Ingest(
        @field:NotBlank val exchange: String,
        @field:NotBlank val baseCode: String,
        @field:NotBlank val quoteCurrency: String,
        @field:Positive val price: BigDecimal,
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
                exchange = result.exchange,
                exchangeRegion = result.exchangeRegion,
                baseCode = result.baseCode,
                quoteCurrency = result.quoteCurrency,
                price = result.price,
                observedAt = result.observedAt,
            )
        }
    }
}
