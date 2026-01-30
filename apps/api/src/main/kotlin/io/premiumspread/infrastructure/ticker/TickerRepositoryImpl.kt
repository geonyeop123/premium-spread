package io.premiumspread.infrastructure.ticker

import io.premiumspread.domain.ticker.Exchange
import io.premiumspread.domain.ticker.Quote
import io.premiumspread.domain.ticker.Symbol
import io.premiumspread.domain.ticker.Ticker
import io.premiumspread.domain.ticker.TickerRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Repository

@Repository
class TickerRepositoryImpl(
    private val tickerJpaRepository: TickerJpaRepository,
) : TickerRepository {

    override fun save(ticker: Ticker): Ticker {
        return tickerJpaRepository.save(ticker)
    }

    override fun findById(id: Long): Ticker? {
        return tickerJpaRepository.findByIdOrNull(id)
    }

    override fun findLatest(exchange: Exchange, quote: Quote): Ticker? {
        return tickerJpaRepository.findLatest(
            exchange = exchange,
            baseCode = quote.baseCode,
            currency = quote.currency,
        )
    }

    override fun findAllByExchangeAndSymbol(exchange: Exchange, symbol: Symbol): List<Ticker> {
        return tickerJpaRepository.findAllByExchangeAndSymbol(
            exchange = exchange,
            symbol = symbol.code,
        )
    }
}
