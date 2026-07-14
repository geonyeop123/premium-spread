package io.premiumspread.domain.notification

import java.math.BigDecimal

class NotificationSubscriptionCommand private constructor() {

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
