package io.premiumspread.domain.batch

import io.premiumspread.domain.market.MarketPair
import io.premiumspread.domain.ticker.Currency
import io.premiumspread.domain.ticker.Quote

/**
 * Batch가 수집/계산할 기본 market 조합이다. 값은 infrastructure configuration adapter가 제공한다.
 * application job은 특정 거래소/심볼을 hard-code하지 않고 이 immutable contract만 사용한다.
 */
data class BatchMarket(
    val pair: MarketPair,
    val koreaQuote: Quote,
    val foreignQuote: Quote,
    val fxBase: Currency,
    val fxQuote: Currency,
) {
    init {
        require(koreaQuote.baseSymbolOrNull() == pair.symbol) { "koreaQuote symbol must match market pair." }
        require(foreignQuote.baseSymbolOrNull() == pair.symbol) { "foreignQuote symbol must match market pair." }
        require(fxBase != fxQuote) { "FX base and quote must differ." }
    }
}

fun interface BatchMarketProvider {
    fun defaultMarket(): BatchMarket
}
