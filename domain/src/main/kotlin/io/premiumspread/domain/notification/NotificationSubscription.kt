package io.premiumspread.domain.notification

import io.premiumspread.domain.BaseEntity
import io.premiumspread.domain.market.MarketPair
import io.premiumspread.domain.ticker.Exchange
import io.premiumspread.domain.ticker.Symbol
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.math.BigDecimal

@Entity
@Table(
    name = "notification_subscription",
    indexes = [
        Index(
            name = "idx_notification_subscription_active_pair_direction",
            columnList = "status,symbol,korea_exchange,foreign_exchange,direction",
        ),
        Index(name = "idx_notification_subscription_member_id", columnList = "member_id"),
    ],
)
class NotificationSubscription protected constructor(
    @Column(name = "member_id", nullable = false, updatable = false)
    val memberId: Long,
    @Column(nullable = false, length = 20, updatable = false)
    val symbol: String,
    koreaExchange: Exchange,
    foreignExchange: Exchange,
    direction: ThresholdDirection,
    threshold: BigDecimal,
    status: SubscriptionStatus,
    revision: Long,
) : BaseEntity() {

    @Enumerated(EnumType.STRING)
    @Column(name = "korea_exchange", nullable = false, length = 50)
    var koreaExchange: Exchange = koreaExchange
        protected set

    @Enumerated(EnumType.STRING)
    @Column(name = "foreign_exchange", nullable = false, length = 50)
    var foreignExchange: Exchange = foreignExchange
        protected set

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

    @Column(nullable = false)
    var revision: Long = revision
        protected set

    /** revision의 read-modify-write가 concurrent update에 유실되지 않게 하는 persistence guard다. */
    @Version
    @Column(name = "lock_version", nullable = false)
    private var lockVersion: Long = 0

    val marketPair: MarketPair
        get() = MarketPair(Symbol(symbol), koreaExchange, foreignExchange)

    fun changeStatus(newStatus: SubscriptionStatus) {
        if (status == newStatus) return
        this.status = newStatus
        revision++
    }

    fun changeThreshold(newThreshold: BigDecimal) {
        if (threshold.compareTo(newThreshold) == 0) return
        this.threshold = newThreshold
        revision++
    }

    fun changeDirection(newDirection: ThresholdDirection) {
        if (direction == newDirection) return
        this.direction = newDirection
        revision++
    }

    fun changeMarketPair(newPair: MarketPair) {
        require(newPair.symbol.code == symbol) { "Subscription symbol cannot be changed." }
        if (marketPair == newPair) return
        koreaExchange = newPair.koreaExchange
        foreignExchange = newPair.foreignExchange
        revision++
    }

    companion object {
        fun create(
            memberId: Long,
            symbol: String,
            direction: ThresholdDirection,
            threshold: BigDecimal,
        ): NotificationSubscription = create(
            memberId = memberId,
            pair = MarketPair.default(Symbol(symbol)),
            direction = direction,
            threshold = threshold,
        )

        fun create(
            memberId: Long,
            pair: MarketPair,
            direction: ThresholdDirection,
            threshold: BigDecimal,
        ): NotificationSubscription = NotificationSubscription(
            memberId = memberId,
            symbol = pair.symbol.code,
            koreaExchange = pair.koreaExchange,
            foreignExchange = pair.foreignExchange,
            direction = direction,
            threshold = threshold,
            status = SubscriptionStatus.ACTIVE,
            revision = 1,
        )
    }
}
