package io.premiumspread.domain.notification

interface NotificationSubscriptionRepository {
    fun save(subscription: NotificationSubscription): NotificationSubscription
    fun findById(id: Long): NotificationSubscription?
    fun findAllByMemberId(memberId: Long): List<NotificationSubscription>
    fun delete(subscription: NotificationSubscription)
}
