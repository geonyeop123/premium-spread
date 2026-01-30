package io.premiumspread.domain.ticker

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class TickerService(
    private val tickerRepository: TickerRepository,
) {

    @Transactional
    fun create(command: TickerCommand.Create): Ticker {
        val quote = createQuote(command.baseCode, command.quoteCurrency)
        val ticker = Ticker.create(
            exchange = command.exchange,
            quote = quote,
            price = command.price,
            observedAt = command.observedAt,
        )
        return tickerRepository.save(ticker)
    }

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

    private fun createQuote(baseCode: String, quoteCurrency: Currency): Quote {
        return if (isCurrencyCode(baseCode)) {
            Quote.fx(Currency.valueOf(baseCode), quoteCurrency)
        } else {
            Quote.coin(Symbol(baseCode), quoteCurrency)
        }
    }

    private fun isCurrencyCode(code: String): Boolean {
        return Currency.entries.any { it.name == code }
    }
}
