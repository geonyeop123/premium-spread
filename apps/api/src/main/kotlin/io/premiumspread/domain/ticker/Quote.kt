package io.premiumspread.domain.ticker

import io.premiumspread.domain.InvalidQuoteException
import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Transient

@Embeddable
data class Quote private constructor(
    @Column(name = "base_code", nullable = false)
    val baseCode: String,
    @Enumerated(EnumType.STRING)
    @Column(name = "base_type", nullable = false)
    val baseType: QuoteBaseType,
    @Enumerated(EnumType.STRING)
    @Column(name = "quote_currency", nullable = false)
    val currency: Currency,
) {
    @get:Transient
    val base: BaseAsset
        get() = resolveBase()

    init {
        if (baseType == QuoteBaseType.CURRENCY && resolveBase() == currency) {
            throw InvalidQuoteException("Base and quote currency must be different for FX quotes.")
        }
    }

    fun baseSymbolOrNull(): Symbol? =
        if (baseType == QuoteBaseType.SYMBOL) Symbol(baseCode) else null

    fun baseCurrencyOrNull(): Currency? =
        if (baseType == QuoteBaseType.CURRENCY) Currency.valueOf(baseCode) else null

    private fun resolveBase(): BaseAsset =
        when (baseType) {
            QuoteBaseType.SYMBOL -> Symbol(baseCode)
            QuoteBaseType.CURRENCY -> Currency.valueOf(baseCode)
        }

    companion object {
        fun coin(base: Symbol, currency: Currency): Quote =
            Quote(base.code, QuoteBaseType.SYMBOL, currency)

        fun fx(base: Currency, currency: Currency): Quote =
            Quote(base.code, QuoteBaseType.CURRENCY, currency)
    }
}

enum class QuoteBaseType {
    SYMBOL,
    CURRENCY,
}
