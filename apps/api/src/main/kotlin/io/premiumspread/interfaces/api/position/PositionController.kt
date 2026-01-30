package io.premiumspread.interfaces.api.position

import io.premiumspread.application.position.PositionCriteria
import io.premiumspread.application.position.PositionFacade
import io.premiumspread.domain.ticker.Exchange
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/positions")
class PositionController(
    private val positionFacade: PositionFacade,
) {

    @PostMapping
    fun open(@RequestBody request: PositionRequest.Open): ResponseEntity<PositionResponse.Detail> {
        val criteria = PositionCriteria.Open(
            symbol = request.symbol,
            exchange = Exchange.valueOf(request.exchange),
            quantity = request.quantity,
            entryPrice = request.entryPrice,
            entryFxRate = request.entryFxRate,
            entryPremiumRate = request.entryPremiumRate,
            entryObservedAt = request.entryObservedAt,
        )
        val result = positionFacade.openPosition(criteria)
        return ResponseEntity.status(HttpStatus.CREATED).body(PositionResponse.Detail.from(result))
    }

    @GetMapping("/{id}")
    fun getById(@PathVariable id: Long): ResponseEntity<PositionResponse.Detail> {
        val result = positionFacade.findById(id)
            ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(PositionResponse.Detail.from(result))
    }

    @GetMapping
    fun getAllOpen(): ResponseEntity<List<PositionResponse.Detail>> {
        val results = positionFacade.findAllOpen()
        return ResponseEntity.ok(results.map { PositionResponse.Detail.from(it) })
    }

    @GetMapping("/{id}/pnl")
    fun getPnl(@PathVariable id: Long): ResponseEntity<PositionResponse.Pnl> {
        val result = positionFacade.calculatePnl(id)
        return ResponseEntity.ok(PositionResponse.Pnl.from(result))
    }

    @PostMapping("/{id}/close")
    fun close(@PathVariable id: Long): ResponseEntity<PositionResponse.Detail> {
        val result = positionFacade.closePosition(id)
        return ResponseEntity.ok(PositionResponse.Detail.from(result))
    }
}
