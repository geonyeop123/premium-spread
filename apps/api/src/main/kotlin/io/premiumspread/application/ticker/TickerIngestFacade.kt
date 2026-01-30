package io.premiumspread.application.ticker

import io.premiumspread.domain.ticker.TickerCommand
import io.premiumspread.domain.ticker.TickerService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class TickerIngestFacade(
    private val tickerService: TickerService,
) {

    @Transactional
    fun ingest(criteria: TickerIngestCriteria): TickerResult {
        val command = TickerCommand.Create(
            exchange = criteria.exchange,
            baseCode = criteria.baseCode,
            quoteCurrency = criteria.quoteCurrency,
            price = criteria.price,
            observedAt = criteria.observedAt,
        )
        val ticker = tickerService.create(command)
        return TickerResult.from(ticker)
    }
}
