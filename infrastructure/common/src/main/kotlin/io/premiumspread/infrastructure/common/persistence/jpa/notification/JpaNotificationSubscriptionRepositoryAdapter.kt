package io.premiumspread.infrastructure.common.persistence.jpa.notification

import io.premiumspread.domain.notification.NotificationSubscription
import io.premiumspread.domain.notification.NotificationSubscriptionRepository
import org.springframework.stereotype.Repository

@Repository
class JpaNotificationSubscriptionRepositoryAdapter(
    private val subscriptionRepository: SpringDataNotificationSubscriptionRepository,
) : NotificationSubscriptionRepository {

    override fun save(subscription: NotificationSubscription): NotificationSubscription =
        subscriptionRepository.save(subscription)

    override fun findById(id: Long): NotificationSubscription? =
        subscriptionRepository.findByIdAndDeletedAtIsNull(id)

    override fun findAllByMemberId(memberId: Long): List<NotificationSubscription> =
        subscriptionRepository.findAllByMemberIdAndDeletedAtIsNull(memberId)

    override fun delete(subscription: NotificationSubscription) {
        subscriptionRepository.save(subscription)
    }
}
