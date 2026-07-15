package io.premiumspread.application.ticker

import io.premiumspread.application.common.ApplicationError
import io.premiumspread.application.common.ApplicationException
import io.premiumspread.domain.InvalidQuoteException
import io.premiumspread.domain.InvalidTickerException
import io.premiumspread.domain.ticker.Currency
import io.premiumspread.domain.ticker.Exchange
import io.premiumspread.domain.ticker.TickerCommand
import io.premiumspread.domain.ticker.Ticker
import io.premiumspread.domain.ticker.TickerService
import org.springframework.stereotype.Service

@Service
class TickerFacade(private val tickerService: TickerService) {
    fun ingest(criteria: TickerCriteria.Ingest): TickerResult.Detail =
        try {
            val ticker = tickerService.create(
                TickerCommand.Create(
                    exchange = parseEnum(criteria.exchange),
                    baseCode = criteria.baseCode,
                    quoteCurrency = parseEnum(criteria.quoteCurrency),
                    price = criteria.price,
                    observedAt = criteria.observedAt,
                ),
            )
            toDetail(ticker)
        } catch (ex: InvalidQuoteException) {
            throw ApplicationException(ApplicationError.INVALID_QUOTE, ex)
        } catch (ex: InvalidTickerException) {
            throw ApplicationException(ApplicationError.INVALID_TICKER, ex)
        }

    private inline fun <reified T : Enum<T>> parseEnum(raw: String): T =
        try {
            enumValueOf<T>(raw)
        } catch (ex: IllegalArgumentException) {
            throw ApplicationException(ApplicationError.INVALID_TICKER, ex)
        }

    private fun toDetail(ticker: Ticker): TickerResult.Detail = TickerResult.Detail(
        id = ticker.id,
        exchange = ticker.exchange.name,
        exchangeRegion = ticker.exchangeRegion.name,
        baseCode = ticker.quote.baseCode,
        quoteCurrency = ticker.quote.currency.name,
        price = ticker.price,
        observedAt = ticker.observedAt,
    )
}
