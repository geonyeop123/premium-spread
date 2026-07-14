package io.premiumspread.infrastructure.batch.cache

import io.premiumspread.domain.exchangerate.ExchangeRateSnapshot
import io.premiumspread.domain.market.FxRateReadPort
import io.premiumspread.domain.market.TickerReadPort
import io.premiumspread.domain.ticker.Currency
import io.premiumspread.domain.ticker.Exchange
import io.premiumspread.domain.ticker.Quote
import io.premiumspread.domain.ticker.TickerSnapshot
import io.premiumspread.infrastructure.common.cache.exchangerate.FxCacheReader
import io.premiumspread.infrastructure.common.cache.ticker.TickerCacheReader

class FxRateReadAdapter(
    private val reader: FxCacheReader,
) : FxRateReadPort {
    override fun findLatest(base: Currency, quote: Currency): ExchangeRateSnapshot? =
        reader.get(base.code, quote.code)?.toDomain()
}

class TickerReadAdapter(
    private val reader: TickerCacheReader,
) : TickerReadPort {
    override fun findLatest(exchange: Exchange, quote: Quote): TickerSnapshot? {
        val symbol = quote.baseSymbolOrNull()?.code ?: return null
        return reader.getSnapshot(exchange.name, symbol)
            ?.takeIf { snapshot ->
                snapshot.currency.equals(quote.currency.code, ignoreCase = true) ||
                    (exchange == Exchange.BINANCE &&
                        quote.currency == Currency.USD &&
                        snapshot.currency.equals("USDT", ignoreCase = true))
            }
    }
}
