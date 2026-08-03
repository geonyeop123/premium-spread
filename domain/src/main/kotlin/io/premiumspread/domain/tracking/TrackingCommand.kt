package io.premiumspread.domain.tracking

import io.premiumspread.domain.ticker.Exchange
import java.math.BigDecimal
import java.time.Instant

class TrackingCommand private constructor() {
    data class Create(
        val memberId: Long,
        val symbol: String,
        val koreaExchange: Exchange,
        val koreaQuantity: BigDecimal,
        val koreaEntryPrice: BigDecimal,
        val foreignExchange: Exchange,
        val foreignQuantity: BigDecimal,
        val foreignEntryPrice: BigDecimal,
        val foreignLeverage: Int,
        val entryFxRate: BigDecimal,
        val entryObservedAt: Instant,
    )
}
