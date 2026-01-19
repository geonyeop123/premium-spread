package io.premiumspread.domain.ticker

data class Quote(
    val base: BaseAsset,
    val currency: Currency,
) {
    init {
        if (base is Currency && base == currency) {
            throw InvalidQuoteException("Base and quote currency must be different for FX quotes.")
        }
    }

    fun baseSymbolOrNull(): Symbol? = base as? Symbol

    fun baseCurrencyOrNull(): Currency? = base as? Currency

    companion object {
        fun coin(base: Symbol, currency: Currency): Quote = Quote(base, currency)

        fun fx(base: Currency, currency: Currency): Quote = Quote(base, currency)
    }
}
