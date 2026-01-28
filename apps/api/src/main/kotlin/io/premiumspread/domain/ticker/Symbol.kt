package io.premiumspread.domain.ticker

import jakarta.persistence.Column
import jakarta.persistence.Embeddable

@Embeddable
data class Symbol private constructor(
    @Column(name = "symbol")
    override val code: String,
) : BaseAsset {
    init {
        require(code.isNotBlank()) { "Symbol must not be blank." }
    }

    companion object {
        operator fun invoke(value: String): Symbol = Symbol(value.trim())
    }
}
