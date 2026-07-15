package io.premiumspread.domain.ticker

import io.premiumspread.domain.market.MarketPair

interface TickerRepository {
    fun save(ticker: Ticker): Ticker
    fun findById(id: Long): Ticker?
    fun findLatest(exchange: Exchange, quote: Quote): Ticker?
    fun findLatestSnapshotByExchangeAndSymbol(exchange: String, symbol: String): TickerSnapshot?
    fun findAllByExchangeAndSymbol(exchange: Exchange, symbol: Symbol): List<Ticker>

    fun findLatestKorea(pair: MarketPair): Ticker? =
        findLatest(pair.koreaExchange, Quote.coin(pair.symbol, Currency.KRW))

    fun findLatestForeign(pair: MarketPair): Ticker? =
        findLatest(pair.foreignExchange, Quote.coin(pair.symbol, Currency.USD))
}
