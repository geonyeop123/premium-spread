package io.premiumspread.interfaces.api.notification

import io.premiumspread.application.notification.NotificationSubscriptionResult
import java.math.BigDecimal

class NotificationSubscriptionResponse private constructor() {

    data class Detail(
        val id: Long,
        val symbol: String,
        val direction: String,
        val threshold: BigDecimal,
        val status: String,
        val koreaExchange: String,
        val foreignExchange: String,
    ) {
        companion object {
            fun from(result: NotificationSubscriptionResult.Detail): Detail = Detail(
                id = result.id,
                symbol = result.symbol,
                direction = result.direction,
                threshold = result.threshold,
                status = result.status,
                koreaExchange = result.koreaExchange,
                foreignExchange = result.foreignExchange,
            )
        }
    }
}
