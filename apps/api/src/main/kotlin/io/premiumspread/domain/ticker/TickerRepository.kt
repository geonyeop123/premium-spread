package io.premiumspread.domain.ticker

interface TickerRepository {
    fun save(ticker: Ticker): Ticker
    fun findById(id: Long): Ticker?
    fun findLatest(exchange: Exchange, quote: Quote): Ticker?
    fun findLatestSnapshotByExchangeAndSymbol(exchange: String, symbol: String): TickerSnapshot?
    fun findAllByExchangeAndSymbol(exchange: Exchange, symbol: Symbol): List<Ticker>
}
