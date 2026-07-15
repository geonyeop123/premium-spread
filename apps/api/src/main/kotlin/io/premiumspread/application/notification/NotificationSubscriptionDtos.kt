package io.premiumspread.application.notification

import java.math.BigDecimal

class NotificationSubscriptionCriteria private constructor() {
    data class Create(
        val memberId: Long,
        val symbol: String,
        val direction: String,
        val threshold: BigDecimal,
        val koreaExchange: String = "BITHUMB",
        val foreignExchange: String = "BINANCE",
    )

    data class Find(val id: Long, val memberId: Long)
    data class FindAll(val memberId: Long)

    data class Update(
        val id: Long,
        val memberId: Long,
        val status: String?,
        val direction: String?,
        val threshold: BigDecimal?,
        val koreaExchange: String? = null,
        val foreignExchange: String? = null,
    )

    data class Delete(val id: Long, val memberId: Long)
}

class NotificationSubscriptionResult private constructor() {
    data class Detail(
        val id: Long,
        val memberId: Long,
        val symbol: String,
        val direction: String,
        val threshold: BigDecimal,
        val status: String,
        val koreaExchange: String,
        val foreignExchange: String,
    )

    data class Details(val items: List<Detail>)
}
