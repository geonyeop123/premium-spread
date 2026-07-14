package io.premiumspread.domain.market

import io.premiumspread.domain.ticker.Exchange
import io.premiumspread.domain.ticker.ExchangeRegion
import io.premiumspread.domain.ticker.Symbol

/** 동일 symbol의 한국 현물과 해외 헤지 거래소 조합을 식별한다. */
class MarketPair(
    val symbol: Symbol,
    val koreaExchange: Exchange,
    val foreignExchange: Exchange,
) {
    init {
        require(koreaExchange.region == ExchangeRegion.KOREA) { "koreaExchange must have KOREA region." }
        require(foreignExchange.region == ExchangeRegion.FOREIGN && foreignExchange != Exchange.FX_PROVIDER) {
            "foreignExchange must be a tradable FOREIGN exchange."
        }
    }

    val canonicalKey: String = "${symbol.code}:${koreaExchange.name}:${foreignExchange.name}"

    override fun equals(other: Any?): Boolean =
        other is MarketPair &&
            symbol == other.symbol &&
            koreaExchange == other.koreaExchange &&
            foreignExchange == other.foreignExchange

    override fun hashCode(): Int = 31 * (31 * symbol.hashCode() + koreaExchange.hashCode()) + foreignExchange.hashCode()

    override fun toString(): String = canonicalKey

    companion object {
        fun default(symbol: Symbol): MarketPair = MarketPair(symbol, Exchange.BITHUMB, Exchange.BINANCE)
    }
}
