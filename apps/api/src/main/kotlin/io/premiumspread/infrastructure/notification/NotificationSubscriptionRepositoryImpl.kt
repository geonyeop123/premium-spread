package io.premiumspread.infrastructure.notification

import io.premiumspread.domain.notification.NotificationSubscription
import io.premiumspread.domain.notification.NotificationSubscriptionRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Repository

@Repository
class NotificationSubscriptionRepositoryImpl(
    private val jpaRepository: NotificationSubscriptionJpaRepository,
) : NotificationSubscriptionRepository {

    override fun save(subscription: NotificationSubscription): NotificationSubscription =
        jpaRepository.save(subscription)

    override fun findById(id: Long): NotificationSubscription? =
        jpaRepository.findByIdOrNull(id)?.takeIf { it.deletedAt == null }

    override fun findAllByMemberId(memberId: Long): List<NotificationSubscription> =
        jpaRepository.findAllByMemberIdAndDeletedAtIsNull(memberId)

    override fun delete(subscription: NotificationSubscription) {
        subscription.delete()
        jpaRepository.save(subscription)
    }
}
