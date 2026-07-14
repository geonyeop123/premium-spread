package io.premiumspread.domain.market

import io.premiumspread.domain.exchangerate.ExchangeRateSnapshot
import io.premiumspread.domain.ticker.Currency
import io.premiumspread.domain.ticker.Exchange
import io.premiumspread.domain.ticker.Quote
import io.premiumspread.domain.ticker.Ticker
import io.premiumspread.domain.ticker.TickerSnapshot
import java.math.BigDecimal
import java.time.Instant

interface TickerReadPort {
    fun findLatest(exchange: Exchange, quote: Quote): TickerSnapshot?
}

interface TickerWritePort {
    fun save(ticker: Ticker): Ticker
}

interface FxRateReadPort {
    fun findLatest(base: Currency, quote: Currency): ExchangeRateSnapshot?
}

interface FxRateWritePort {
    fun save(snapshot: ExchangeRateSnapshot)
}

data class MarketTick(
    val exchange: Exchange,
    val quote: Quote,
    val price: BigDecimal,
    val observedAt: Instant,
)

fun interface TickerSink {
    fun accept(tick: MarketTick)
}

interface MarketTickerStream {
    fun start(sink: TickerSink)

    fun stop()
}
