package io.premiumspread.domain.ticker

import jakarta.persistence.Column
import jakarta.persistence.Embeddable

@Embeddable
class Symbol private constructor(
    @Column(name = "symbol")
    override val code: String,
) : BaseAsset {
    init {
        require(code.isNotBlank()) { "Symbol must not be blank." }
    }

    override fun equals(other: Any?): Boolean = other is Symbol && code == other.code

    override fun hashCode(): Int = code.hashCode()

    override fun toString(): String = code

    companion object {
        operator fun invoke(value: String): Symbol = Symbol(value.trim().uppercase())
    }
}
