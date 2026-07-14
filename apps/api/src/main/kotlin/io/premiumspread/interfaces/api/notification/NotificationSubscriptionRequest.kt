package io.premiumspread.interfaces.api.notification

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import java.math.BigDecimal

class NotificationSubscriptionRequest private constructor() {

    data class Create(
        @field:NotBlank val symbol: String,
        @field:NotBlank val direction: String,
        @field:NotNull val threshold: BigDecimal,
    )

    data class Update(
        val status: String? = null,
        val direction: String? = null,
        val threshold: BigDecimal? = null,
    )
}
