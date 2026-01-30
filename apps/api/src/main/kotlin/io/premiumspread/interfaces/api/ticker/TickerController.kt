package io.premiumspread.interfaces.api.ticker

import io.premiumspread.application.ticker.TickerCriteria
import io.premiumspread.application.ticker.TickerIngestFacade
import io.premiumspread.domain.ticker.Currency
import io.premiumspread.domain.ticker.Exchange
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/tickers")
class TickerController(
    private val tickerIngestFacade: TickerIngestFacade,
) {

    @PostMapping
    fun ingest(@RequestBody request: TickerRequest.Ingest): ResponseEntity<TickerResponse.Detail> {
        val criteria = TickerCriteria.Ingest(
            exchange = Exchange.valueOf(request.exchange),
            baseCode = request.baseCode,
            quoteCurrency = Currency.valueOf(request.quoteCurrency),
            price = request.price,
            observedAt = request.observedAt,
        )
        val result = tickerIngestFacade.ingest(criteria)
        return ResponseEntity.status(HttpStatus.CREATED).body(TickerResponse.Detail.from(result))
    }
}
