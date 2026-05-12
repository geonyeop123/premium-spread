package io.premiumspread.domain.notification

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class NotificationSubscriptionService(
    private val repository: NotificationSubscriptionRepository,
) {

    @Transactional
    fun create(command: NotificationSubscriptionCommand.Create): NotificationSubscription {
        val sub = NotificationSubscription.create(
            memberId = command.memberId,
            symbol = command.symbol,
            direction = command.direction,
            threshold = command.threshold,
        )
        return repository.save(sub)
    }

    @Transactional(readOnly = true)
    fun findByIdAndMemberId(id: Long, memberId: Long): NotificationSubscription? {
        val sub = repository.findById(id) ?: return null
        return if (sub.memberId == memberId) sub else null
    }

    @Transactional(readOnly = true)
    fun findAllByMemberId(memberId: Long): List<NotificationSubscription> =
        repository.findAllByMemberId(memberId)

    @Transactional
    fun update(command: NotificationSubscriptionCommand.Update): NotificationSubscription {
        val sub = findByIdAndMemberId(command.id, command.memberId)
            ?: throw NotificationSubscriptionNotFoundException("구독을 찾을 수 없습니다: id=${command.id}")

        command.status?.let { sub.changeStatus(it) }
        command.direction?.let { sub.changeDirection(it) }
        command.threshold?.let { sub.changeThreshold(it) }

        return repository.save(sub)
    }

    @Transactional
    fun delete(id: Long, memberId: Long) {
        val sub = findByIdAndMemberId(id, memberId)
            ?: throw NotificationSubscriptionNotFoundException("구독을 찾을 수 없습니다: id=$id")
        repository.delete(sub)
    }
}
