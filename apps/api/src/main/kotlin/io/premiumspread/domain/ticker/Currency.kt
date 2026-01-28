package io.premiumspread.domain.ticker

enum class Currency(
    override val code: String,
) : BaseAsset {
    KRW("KRW"),
    USD("USD"),
}
