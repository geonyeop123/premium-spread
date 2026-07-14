package io.premiumspread.domain.notification

import io.premiumspread.domain.ticker.Exchange
import java.math.BigDecimal

class NotificationSubscriptionCommand private constructor() {

    data class Create(
        val memberId: Long,
        val symbol: String,
        val direction: ThresholdDirection,
        val threshold: BigDecimal,
        val koreaExchange: Exchange = Exchange.BITHUMB,
        val foreignExchange: Exchange = Exchange.BINANCE,
    )

    data class Update(
        val id: Long,
        val memberId: Long,
        val status: SubscriptionStatus?,
        val direction: ThresholdDirection?,
        val threshold: BigDecimal?,
        val koreaExchange: Exchange? = null,
        val foreignExchange: Exchange? = null,
    ) {
        init {
            require((koreaExchange == null) == (foreignExchange == null)) {
                "koreaExchange and foreignExchange must be changed together."
            }
        }
    }
}
