package io.premiumspread.application.notification

import io.premiumspread.domain.notification.NotificationSubscription
import io.premiumspread.domain.notification.SubscriptionStatus
import io.premiumspread.domain.notification.ThresholdDirection
import java.math.BigDecimal

class NotificationSubscriptionCriteria private constructor() {
    data class Create(
        val memberId: Long,
        val symbol: String,
        val direction: ThresholdDirection,
        val threshold: BigDecimal,
    )

    data class Update(
        val id: Long,
        val memberId: Long,
        val status: SubscriptionStatus?,
        val direction: ThresholdDirection?,
        val threshold: BigDecimal?,
    )
}

class NotificationSubscriptionResult private constructor() {
    data class Detail(
        val id: Long,
        val memberId: Long,
        val symbol: String,
        val direction: ThresholdDirection,
        val threshold: BigDecimal,
        val status: SubscriptionStatus,
    ) {
        companion object {
            fun from(entity: NotificationSubscription): Detail = Detail(
                id = entity.id,
                memberId = entity.memberId,
                symbol = entity.symbol,
                direction = entity.direction,
                threshold = entity.threshold,
                status = entity.status,
            )
        }
    }
}
