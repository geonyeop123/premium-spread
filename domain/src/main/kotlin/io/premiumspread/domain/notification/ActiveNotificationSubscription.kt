package io.premiumspread.domain.notification

import io.premiumspread.domain.market.MarketPair
import java.math.BigDecimal

data class ActiveNotificationSubscription(
    val id: Long,
    val memberId: Long,
    val memberEmail: String,
    val memberNickname: String,
    val pair: MarketPair,
    val revision: Long,
    val direction: ThresholdDirection,
    val threshold: BigDecimal,
) {
    val symbol: String = pair.symbol.code

    fun matches(rate: BigDecimal): Boolean = when (direction) {
        ThresholdDirection.ABOVE -> rate >= threshold
        ThresholdDirection.BELOW -> rate <= threshold
    }
}

fun interface ActiveNotificationSubscriptionPort {
    fun findActiveByPair(pair: MarketPair): List<ActiveNotificationSubscription>
}
