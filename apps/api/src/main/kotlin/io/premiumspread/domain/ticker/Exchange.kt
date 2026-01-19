package io.premiumspread.domain.ticker

enum class Exchange(
    val region: ExchangeRegion,
) {
    UPBIT(ExchangeRegion.KOREA),
    BITHUMB(ExchangeRegion.KOREA),
    BINANCE(ExchangeRegion.FOREIGN),
    FX_PROVIDER(ExchangeRegion.FOREIGN),
}
