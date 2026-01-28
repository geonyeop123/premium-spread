package io.premiumspread.application.ticker

import io.premiumspread.domain.ticker.Currency
import io.premiumspread.domain.ticker.Quote
import io.premiumspread.domain.ticker.QuoteBaseType
import io.premiumspread.domain.ticker.Symbol
import io.premiumspread.domain.ticker.Ticker
import io.premiumspread.domain.ticker.TickerRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class TickerIngestFacade(
    private val tickerRepository: TickerRepository,
) {

    @Transactional
    fun ingest(criteria: TickerIngestCriteria): TickerResult {
        val quote = createQuote(criteria.baseCode, criteria.quoteCurrency)
        val ticker = Ticker.create(
            exchange = criteria.exchange,
            quote = quote,
            price = criteria.price,
            observedAt = criteria.observedAt,
        )
        val savedTicker = tickerRepository.save(ticker)
        return TickerResult.from(savedTicker)
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
