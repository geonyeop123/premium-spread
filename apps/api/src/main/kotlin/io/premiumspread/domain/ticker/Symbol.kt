package io.premiumspread.domain.ticker

data class Symbol(
    val value: String,
) : BaseAsset {
    init {
        val normalized = value.trim()
        require(normalized.isNotEmpty()) { "Symbol must not be blank." }
    }

    override val code: String = value.trim()
}
