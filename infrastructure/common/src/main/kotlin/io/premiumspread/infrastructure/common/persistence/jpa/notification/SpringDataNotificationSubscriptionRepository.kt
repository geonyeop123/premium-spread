package io.premiumspread.infrastructure.common.persistence.jpa.notification

import io.premiumspread.domain.notification.NotificationSubscription
import org.springframework.data.jpa.repository.JpaRepository

interface SpringDataNotificationSubscriptionRepository : JpaRepository<NotificationSubscription, Long> {
    fun findByIdAndDeletedAtIsNull(id: Long): NotificationSubscription?
    fun findAllByMemberIdAndDeletedAtIsNull(memberId: Long): List<NotificationSubscription>
}
