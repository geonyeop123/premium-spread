package io.premiumspread.interfaces.api

import io.premiumspread.application.ticker.TickerIngestCriteria
import io.premiumspread.application.ticker.TickerIngestFacade
import io.premiumspread.application.ticker.TickerResult
import io.premiumspread.domain.ticker.Currency
import io.premiumspread.domain.ticker.Exchange
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.time.Instant

@RestController
@RequestMapping("/api/v1/tickers")
class TickerController(
    private val tickerIngestFacade: TickerIngestFacade,
) {

    @PostMapping
    fun ingest(@RequestBody request: TickerIngestRequest): ResponseEntity<TickerResponse> {
        val criteria = TickerIngestCriteria(
            exchange = Exchange.valueOf(request.exchange),
            baseCode = request.baseCode,
            quoteCurrency = Currency.valueOf(request.quoteCurrency),
            price = request.price,
            observedAt = request.observedAt,
        )
        val result = tickerIngestFacade.ingest(criteria)
        return ResponseEntity.status(HttpStatus.CREATED).body(TickerResponse.from(result))
    }
}

data class TickerIngestRequest(
    val exchange: String,
    val baseCode: String,
    val quoteCurrency: String,
    val price: BigDecimal,
    val observedAt: Instant,
)

data class TickerResponse(
    val id: Long,
    val exchange: String,
    val exchangeRegion: String,
    val baseCode: String,
    val quoteCurrency: String,
    val price: BigDecimal,
    val observedAt: Instant,
) {
    companion object {
        fun from(result: TickerResult): TickerResponse = TickerResponse(
            id = result.id,
            exchange = result.exchange.name,
            exchangeRegion = result.exchangeRegion.name,
            baseCode = result.baseCode,
            quoteCurrency = result.quoteCurrency.name,
            price = result.price,
            observedAt = result.observedAt,
        )
    }
}
