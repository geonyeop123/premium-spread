package io.premiumspread.application.notification

import io.premiumspread.domain.notification.NotificationSubscriptionCommand
import io.premiumspread.domain.notification.NotificationSubscriptionNotFoundException
import io.premiumspread.domain.notification.NotificationSubscriptionService
import org.springframework.stereotype.Service

@Service
class NotificationSubscriptionFacade(
    private val service: NotificationSubscriptionService,
) {

    fun create(criteria: NotificationSubscriptionCriteria.Create): NotificationSubscriptionResult.Detail {
        val saved = service.create(
            NotificationSubscriptionCommand.Create(
                memberId = criteria.memberId,
                symbol = criteria.symbol,
                direction = criteria.direction,
                threshold = criteria.threshold,
            ),
        )
        return NotificationSubscriptionResult.Detail.from(saved)
    }

    fun findByIdAndMemberId(id: Long, memberId: Long): NotificationSubscriptionResult.Detail {
        val sub = service.findByIdAndMemberId(id, memberId)
            ?: throw NotificationSubscriptionNotFoundException("구독을 찾을 수 없습니다: id=$id")
        return NotificationSubscriptionResult.Detail.from(sub)
    }

    fun findAllByMemberId(memberId: Long): List<NotificationSubscriptionResult.Detail> =
        service.findAllByMemberId(memberId).map { NotificationSubscriptionResult.Detail.from(it) }

    fun update(criteria: NotificationSubscriptionCriteria.Update): NotificationSubscriptionResult.Detail {
        val updated = service.update(
            NotificationSubscriptionCommand.Update(
                id = criteria.id,
                memberId = criteria.memberId,
                status = criteria.status,
                direction = criteria.direction,
                threshold = criteria.threshold,
            ),
        )
        return NotificationSubscriptionResult.Detail.from(updated)
    }

    fun delete(id: Long, memberId: Long) {
        service.delete(id, memberId)
    }
}
