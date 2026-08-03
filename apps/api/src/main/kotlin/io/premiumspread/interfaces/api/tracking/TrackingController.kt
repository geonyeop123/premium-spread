package io.premiumspread.interfaces.api.tracking

import io.premiumspread.application.tracking.TrackingCriteria
import io.premiumspread.application.tracking.TrackingFacade
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
@RequestMapping("/api/v1/trackings")
class TrackingController(private val trackingFacade: TrackingFacade) {

    @PostMapping("/from-market")
    fun recordFromMarket(
        @LoginMemberId memberId: Long,
        @Valid @RequestBody request: TrackingRequest.RecordFromMarket,
    ): ResponseEntity<TrackingResponse.Detail> {
        val criteria = TrackingCriteria.RecordFromMarket(
            memberId = memberId,
            symbol = request.symbol,
            koreaExchange = request.koreaExchange,
            koreaQuantity = request.koreaQuantity,
            foreignExchange = request.foreignExchange,
            foreignQuantity = request.foreignQuantity,
            foreignLeverage = request.foreignLeverage,
        )
        val result = trackingFacade.recordFromMarket(criteria)
        return ResponseEntity.status(HttpStatus.CREATED).body(TrackingResponse.Detail.from(result))
    }

    @PostMapping
    fun record(
        @LoginMemberId memberId: Long,
        @Valid @RequestBody request: TrackingRequest.Record,
    ): ResponseEntity<TrackingResponse.Detail> {
        val criteria = TrackingCriteria.Record(
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
        val result = trackingFacade.record(criteria)
        return ResponseEntity.status(HttpStatus.CREATED).body(TrackingResponse.Detail.from(result))
    }

    @GetMapping("/summary")
    fun getSummary(@LoginMemberId memberId: Long): ResponseEntity<TrackingResponse.Summary> {
        val result = trackingFacade.getSummary(TrackingCriteria.Summary(memberId))
        return ResponseEntity.ok(TrackingResponse.Summary.from(result))
    }

    @GetMapping("/archived")
    fun findAllArchived(@LoginMemberId memberId: Long): ResponseEntity<List<TrackingResponse.Detail>> {
        val result = trackingFacade.findAllArchivedByMemberId(TrackingCriteria.FindAllArchived(memberId))
        return ResponseEntity.ok(result.items.map { TrackingResponse.Detail.from(it) })
    }

    @GetMapping("/{id}")
    fun getById(
        @PathVariable id: Long,
        @LoginMemberId memberId: Long,
    ): ResponseEntity<TrackingResponse.Detail> {
        val result = trackingFacade.findById(TrackingCriteria.FindById(id, memberId))
        return ResponseEntity.ok(TrackingResponse.Detail.from(result))
    }

    @GetMapping
    fun findAllActive(@LoginMemberId memberId: Long): ResponseEntity<List<TrackingResponse.Detail>> {
        val result = trackingFacade.findAllActiveByMemberId(TrackingCriteria.FindAllActive(memberId))
        return ResponseEntity.ok(result.items.map { TrackingResponse.Detail.from(it) })
    }

    @GetMapping("/{id}/gross-pnl")
    fun getGrossPnl(
        @PathVariable id: Long,
        @LoginMemberId memberId: Long,
    ): ResponseEntity<TrackingResponse.GrossPnl> {
        val result = trackingFacade.getGrossPnl(TrackingCriteria.GetGrossPnl(id, memberId))
        return ResponseEntity.ok(TrackingResponse.GrossPnl.from(result))
    }

    @PostMapping("/{id}/archive")
    fun archive(
        @PathVariable id: Long,
        @LoginMemberId memberId: Long,
    ): ResponseEntity<TrackingResponse.Detail> {
        val result = trackingFacade.archive(TrackingCriteria.Archive(id, memberId))
        return ResponseEntity.ok(TrackingResponse.Detail.from(result))
    }
}
