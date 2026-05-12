package io.premiumspread.interfaces.api.notification

import io.premiumspread.domain.notification.SubscriptionStatus
import io.premiumspread.domain.notification.ThresholdDirection
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import java.math.BigDecimal

class NotificationSubscriptionRequest private constructor() {

    data class Create(
        @field:NotBlank val symbol: String,
        @field:NotNull val direction: ThresholdDirection,
        @field:NotNull val threshold: BigDecimal,
    )

    data class Update(
        val status: SubscriptionStatus? = null,
        val direction: ThresholdDirection? = null,
        val threshold: BigDecimal? = null,
    )
}
