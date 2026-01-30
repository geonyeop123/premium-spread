package io.premiumspread.interfaces.api.position

import io.premiumspread.application.position.PositionFacade
import io.premiumspread.application.position.PositionOpenCriteria
import io.premiumspread.application.position.PositionPnlResult
import io.premiumspread.application.position.PositionResult
import io.premiumspread.domain.ticker.Exchange
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.time.Instant

@RestController
@RequestMapping("/api/v1/positions")
class PositionController(
    private val positionFacade: PositionFacade,
) {

    @PostMapping
    fun open(@RequestBody request: PositionOpenRequest): ResponseEntity<PositionResponse> {
        val criteria = PositionOpenCriteria(
            symbol = request.symbol,
            exchange = Exchange.valueOf(request.exchange),
            quantity = request.quantity,
            entryPrice = request.entryPrice,
            entryFxRate = request.entryFxRate,
            entryPremiumRate = request.entryPremiumRate,
            entryObservedAt = request.entryObservedAt,
        )
        val result = positionFacade.openPosition(criteria)
        return ResponseEntity.status(HttpStatus.CREATED).body(PositionResponse.from(result))
    }

    @GetMapping("/{id}")
    fun getById(@PathVariable id: Long): ResponseEntity<PositionResponse> {
        val result = positionFacade.findById(id)
            ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(PositionResponse.from(result))
    }

    @GetMapping
    fun getAllOpen(): ResponseEntity<List<PositionResponse>> {
        val results = positionFacade.findAllOpen()
        return ResponseEntity.ok(results.map { PositionResponse.from(it) })
    }

    @GetMapping("/{id}/pnl")
    fun getPnl(@PathVariable id: Long): ResponseEntity<PositionPnlResponse> {
        val result = positionFacade.calculatePnl(id)
        return ResponseEntity.ok(PositionPnlResponse.from(result))
    }

    @PostMapping("/{id}/close")
    fun close(@PathVariable id: Long): ResponseEntity<PositionResponse> {
        val result = positionFacade.closePosition(id)
        return ResponseEntity.ok(PositionResponse.from(result))
    }
}

data class PositionOpenRequest(
    val symbol: String,
    val exchange: String,
    val quantity: BigDecimal,
    val entryPrice: BigDecimal,
    val entryFxRate: BigDecimal,
    val entryPremiumRate: BigDecimal,
    val entryObservedAt: Instant,
)

data class PositionResponse(
    val id: Long,
    val symbol: String,
    val exchange: String,
    val quantity: BigDecimal,
    val entryPrice: BigDecimal,
    val entryFxRate: BigDecimal,
    val entryPremiumRate: BigDecimal,
    val entryObservedAt: Instant,
    val status: String,
) {
    companion object {
        fun from(result: PositionResult): PositionResponse = PositionResponse(
            id = result.id,
            symbol = result.symbol,
            exchange = result.exchange.name,
            quantity = result.quantity,
            entryPrice = result.entryPrice,
            entryFxRate = result.entryFxRate,
            entryPremiumRate = result.entryPremiumRate,
            entryObservedAt = result.entryObservedAt,
            status = result.status.name,
        )
    }
}

data class PositionPnlResponse(
    val positionId: Long,
    val premiumDiff: BigDecimal,
    val entryPremiumRate: BigDecimal,
    val currentPremiumRate: BigDecimal,
    val isProfit: Boolean,
    val calculatedAt: Instant,
) {
    companion object {
        fun from(result: PositionPnlResult): PositionPnlResponse = PositionPnlResponse(
            positionId = result.positionId,
            premiumDiff = result.premiumDiff,
            entryPremiumRate = result.entryPremiumRate,
            currentPremiumRate = result.currentPremiumRate,
            isProfit = result.isProfit,
            calculatedAt = result.calculatedAt,
        )
    }
}
