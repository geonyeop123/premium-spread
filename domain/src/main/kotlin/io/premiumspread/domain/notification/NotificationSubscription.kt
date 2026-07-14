package io.premiumspread.domain.notification

import io.premiumspread.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Index
import jakarta.persistence.Table
import java.math.BigDecimal

@Entity
@Table(
    name = "notification_subscription",
    indexes = [
        Index(name = "idx_notification_subscription_status_symbol", columnList = "status,symbol"),
        Index(name = "idx_notification_subscription_member_id", columnList = "member_id"),
    ],
)
class NotificationSubscription protected constructor(
    @Column(name = "member_id", nullable = false, updatable = false)
    val memberId: Long,
    @Column(nullable = false, length = 20, updatable = false)
    val symbol: String,
    direction: ThresholdDirection,
    threshold: BigDecimal,
    status: SubscriptionStatus,
) : BaseEntity() {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    var direction: ThresholdDirection = direction
        protected set

    @Column(nullable = false, precision = 10, scale = 4)
    var threshold: BigDecimal = threshold
        protected set

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: SubscriptionStatus = status
        protected set

    fun changeStatus(newStatus: SubscriptionStatus) {
        this.status = newStatus
    }

    fun changeThreshold(newThreshold: BigDecimal) {
        this.threshold = newThreshold
    }

    fun changeDirection(newDirection: ThresholdDirection) {
        this.direction = newDirection
    }

    companion object {
        fun create(
            memberId: Long,
            symbol: String,
            direction: ThresholdDirection,
            threshold: BigDecimal,
        ): NotificationSubscription = NotificationSubscription(
            memberId = memberId,
            symbol = symbol.uppercase(),
            direction = direction,
            threshold = threshold,
            status = SubscriptionStatus.ACTIVE,
        )
    }
}
