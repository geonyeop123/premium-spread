package io.premiumspread.domain.ticker

import java.math.BigDecimal
import java.time.Instant

class TickerCommand private constructor() {
    data class Create(
        val exchange: Exchange,
        val baseCode: String,
        val quoteCurrency: Currency,
        val price: BigDecimal,
        val observedAt: Instant,
    )
}
