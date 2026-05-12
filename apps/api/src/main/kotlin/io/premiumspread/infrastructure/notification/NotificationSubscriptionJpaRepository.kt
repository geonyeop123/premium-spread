package io.premiumspread.infrastructure.notification

import io.premiumspread.domain.notification.NotificationSubscription
import org.springframework.data.jpa.repository.JpaRepository

interface NotificationSubscriptionJpaRepository : JpaRepository<NotificationSubscription, Long> {
    fun findAllByMemberIdAndDeletedAtIsNull(memberId: Long): List<NotificationSubscription>
}
