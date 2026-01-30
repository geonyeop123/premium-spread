package io.premiumspread.domain.premium

import io.premiumspread.domain.ticker.Ticker

class PremiumCommand private constructor() {
    data class Create(
        val koreaTicker: Ticker,
        val foreignTicker: Ticker,
        val fxTicker: Ticker,
    )
}
