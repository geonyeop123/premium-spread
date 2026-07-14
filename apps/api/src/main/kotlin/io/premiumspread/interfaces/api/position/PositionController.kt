package io.premiumspread.interfaces.api.position

import io.premiumspread.application.position.PositionCriteria
import io.premiumspread.application.position.PositionFacade
import io.premiumspread.interfaces.api.auth.LoginMemberId
import jakarta.validation.Valid
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

    @PostMapping("/auto")
    fun openAuto(
        @LoginMemberId memberId: Long,
        @Valid @RequestBody request: PositionRequest.OpenAuto,
    ): ResponseEntity<PositionResponse.Detail> {
        val criteria = PositionCriteria.OpenAuto(
            memberId = memberId,
            symbol = request.symbol,
            koreaExchange = request.koreaExchange,
            koreaQuantity = request.koreaQuantity,
            foreignExchange = request.foreignExchange,
            foreignQuantity = request.foreignQuantity,
            foreignLeverage = request.foreignLeverage,
        )
        val result = positionFacade.openAutoPosition(criteria)
        return ResponseEntity.status(HttpStatus.CREATED).body(PositionResponse.Detail.from(result))
    }

    @PostMapping("/manual")
    fun openManual(
        @LoginMemberId memberId: Long,
        @Valid @RequestBody request: PositionRequest.OpenManual,
    ): ResponseEntity<PositionResponse.Detail> {
        val criteria = PositionCriteria.OpenManual(
            memberId = memberId,
            symbol = request.symbol,
            koreaExchange = request.koreaExchange,
            koreaQuantity = request.koreaQuantity,
            koreaEntryPrice = request.koreaEntryPrice,
            foreignExchange = request.foreignExchange,
            foreignQuantity = request.foreignQuantity,
            foreignEntryPrice = request.foreignEntryPrice,
            foreignLeverage = request.foreignLeverage,
            entryFxRate = request.entryFxRate,
            entryObservedAt = request.entryObservedAt,
        )
        val result = positionFacade.openManualPosition(criteria)
        return ResponseEntity.status(HttpStatus.CREATED).body(PositionResponse.Detail.from(result))
    }

    @GetMapping("/summary")
    fun getSummary(@LoginMemberId memberId: Long): ResponseEntity<PositionResponse.Summary> {
        val result = positionFacade.getSummary(PositionCriteria.Summary(memberId))
        return ResponseEntity.ok(PositionResponse.Summary.from(result))
    }

    @GetMapping("/history")
    fun getHistory(@LoginMemberId memberId: Long): ResponseEntity<List<PositionResponse.Detail>> {
        val result = positionFacade.findAllClosedByMemberId(PositionCriteria.FindAllClosed(memberId))
        return ResponseEntity.ok(result.items.map { PositionResponse.Detail.from(it) })
    }

    @GetMapping("/{id}")
    fun getById(
        @PathVariable id: Long,
        @LoginMemberId memberId: Long,
    ): ResponseEntity<PositionResponse.Detail> {
        val result = positionFacade.findById(PositionCriteria.FindById(id, memberId))
        return ResponseEntity.ok(PositionResponse.Detail.from(result))
    }

    @GetMapping
    fun getAllOpen(@LoginMemberId memberId: Long): ResponseEntity<List<PositionResponse.Detail>> {
        val result = positionFacade.findAllOpenByMemberId(PositionCriteria.FindAllOpen(memberId))
        return ResponseEntity.ok(result.items.map { PositionResponse.Detail.from(it) })
    }

    @GetMapping("/{id}/pnl")
    fun getPnl(
        @PathVariable id: Long,
        @LoginMemberId memberId: Long,
    ): ResponseEntity<PositionResponse.Pnl> {
        val result = positionFacade.calculatePnl(PositionCriteria.CalculatePnl(id, memberId))
        return ResponseEntity.ok(PositionResponse.Pnl.from(result))
    }

    @PostMapping("/{id}/close")
    fun close(
        @PathVariable id: Long,
        @LoginMemberId memberId: Long,
    ): ResponseEntity<PositionResponse.Detail> {
        val result = positionFacade.closePosition(PositionCriteria.Close(id, memberId))
        return ResponseEntity.ok(PositionResponse.Detail.from(result))
    }
}
