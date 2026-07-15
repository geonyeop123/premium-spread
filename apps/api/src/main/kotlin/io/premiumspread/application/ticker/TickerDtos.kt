package io.premiumspread.application.ticker

import java.math.BigDecimal
import java.time.Instant

class TickerCriteria private constructor() {
    data class Ingest(
        val exchange: String,
        val baseCode: String,
        val quoteCurrency: String,
        val price: BigDecimal,
        val observedAt: Instant,
    )
}

class TickerResult private constructor() {
    data class Detail(
        val id: Long,
        val exchange: String,
        val exchangeRegion: String,
        val baseCode: String,
        val quoteCurrency: String,
        val price: BigDecimal,
        val observedAt: Instant,
    )
}
