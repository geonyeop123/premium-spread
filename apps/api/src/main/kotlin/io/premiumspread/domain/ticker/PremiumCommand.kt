package io.premiumspread.domain.ticker

class PremiumCommand private constructor() {
    data class Create(
        val koreaTicker: Ticker,
        val foreignTicker: Ticker,
        val fxTicker: Ticker,
    )
}
