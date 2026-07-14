package io.premiumspread.interfaces.api.notification

import io.premiumspread.application.notification.NotificationSubscriptionCriteria
import io.premiumspread.application.notification.NotificationSubscriptionFacade
import io.premiumspread.interfaces.api.auth.LoginMemberId
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/notifications/subscriptions")
class NotificationSubscriptionController(
    private val facade: NotificationSubscriptionFacade,
) {

    @PostMapping
    fun create(
        @LoginMemberId memberId: Long,
        @Valid @RequestBody request: NotificationSubscriptionRequest.Create,
    ): ResponseEntity<NotificationSubscriptionResponse.Detail> {
        val result = facade.create(
            NotificationSubscriptionCriteria.Create(
                memberId = memberId,
                symbol = request.symbol,
                direction = request.direction,
                threshold = request.threshold,
            ),
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(NotificationSubscriptionResponse.Detail.from(result))
    }

    @GetMapping
    fun list(@LoginMemberId memberId: Long): ResponseEntity<List<NotificationSubscriptionResponse.Detail>> {
        val result = facade.findAll(NotificationSubscriptionCriteria.FindAll(memberId))
        return ResponseEntity.ok(result.items.map { NotificationSubscriptionResponse.Detail.from(it) })
    }

    @GetMapping("/{id}")
    fun detail(
        @LoginMemberId memberId: Long,
        @PathVariable id: Long,
    ): ResponseEntity<NotificationSubscriptionResponse.Detail> {
        val result = facade.find(NotificationSubscriptionCriteria.Find(id, memberId))
        return ResponseEntity.ok(NotificationSubscriptionResponse.Detail.from(result))
    }

    @PatchMapping("/{id}")
    fun update(
        @LoginMemberId memberId: Long,
        @PathVariable id: Long,
        @Valid @RequestBody request: NotificationSubscriptionRequest.Update,
    ): ResponseEntity<NotificationSubscriptionResponse.Detail> {
        val result = facade.update(
            NotificationSubscriptionCriteria.Update(
                id = id,
                memberId = memberId,
                status = request.status,
                direction = request.direction,
                threshold = request.threshold,
            ),
        )
        return ResponseEntity.ok(NotificationSubscriptionResponse.Detail.from(result))
    }

    @DeleteMapping("/{id}")
    fun delete(
        @LoginMemberId memberId: Long,
        @PathVariable id: Long,
    ): ResponseEntity<Void> {
        facade.delete(NotificationSubscriptionCriteria.Delete(id, memberId))
        return ResponseEntity.noContent().build()
    }
}
