package io.premiumspread.domain.ticker

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class TickerService(
    private val tickerRepository: TickerRepository,
) {

    @Transactional
    fun save(ticker: Ticker): Ticker {
        return tickerRepository.save(ticker)
    }

    @Transactional(readOnly = true)
    fun findLatest(exchange: Exchange, quote: Quote): Ticker? {
        return tickerRepository.findLatest(exchange, quote)
    }

    @Transactional(readOnly = true)
    fun findById(id: Long): Ticker? {
        return tickerRepository.findById(id)
    }
}
