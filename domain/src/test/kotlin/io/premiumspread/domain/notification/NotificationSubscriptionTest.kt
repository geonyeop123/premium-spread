package io.premiumspread.domain.notification

import io.premiumspread.domain.market.MarketPair
import io.premiumspread.domain.ticker.Exchange
import io.premiumspread.domain.ticker.Symbol
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class NotificationSubscriptionTest {

    @Test
    fun `create는 ACTIVE 상태로 구독을 생성한다`() {
        val sub = NotificationSubscription.create(
            memberId = 1L,
            symbol = "BTC",
            direction = ThresholdDirection.ABOVE,
            threshold = BigDecimal("5.00"),
        )
        assertThat(sub.memberId).isEqualTo(1L)
        assertThat(sub.symbol).isEqualTo("BTC")
        assertThat(sub.direction).isEqualTo(ThresholdDirection.ABOVE)
        assertThat(sub.threshold).isEqualByComparingTo("5.00")
        assertThat(sub.status).isEqualTo(SubscriptionStatus.ACTIVE)
        assertThat(sub.revision).isEqualTo(1)
        assertThat(sub.marketPair).isEqualTo(MarketPair.default(Symbol("BTC")))
    }

    @Test
    fun `create는 symbol을 uppercase로 정규화한다`() {
        val sub = NotificationSubscription.create(1L, "btc", ThresholdDirection.ABOVE, BigDecimal("5.00"))
        assertThat(sub.symbol).isEqualTo("BTC")
    }

    @Test
    fun `changeStatus는 동일 인스턴스의 status만 바꾼다 (id 보존)`() {
        val sub = NotificationSubscription.create(1L, "BTC", ThresholdDirection.ABOVE, BigDecimal("5.00"))
        sub.changeStatus(SubscriptionStatus.INACTIVE)
        assertThat(sub.status).isEqualTo(SubscriptionStatus.INACTIVE)
        assertThat(sub.revision).isEqualTo(2)
    }

    @Test
    fun `changeThreshold는 임계값만 바꾼다`() {
        val sub = NotificationSubscription.create(1L, "BTC", ThresholdDirection.ABOVE, BigDecimal("5.00"))
        sub.changeThreshold(BigDecimal("7.50"))
        assertThat(sub.threshold).isEqualByComparingTo("7.50")
        assertThat(sub.revision).isEqualTo(2)
    }

    @Test
    fun `changeDirection은 방향만 바꾼다`() {
        val sub = NotificationSubscription.create(1L, "BTC", ThresholdDirection.ABOVE, BigDecimal("5.00"))
        sub.changeDirection(ThresholdDirection.BELOW)
        assertThat(sub.direction).isEqualTo(ThresholdDirection.BELOW)
        assertThat(sub.revision).isEqualTo(2)
    }

    @Test
    fun `이벤트 의미가 같은 값으로 변경하면 revision을 증가시키지 않는다`() {
        val sub = NotificationSubscription.create(1L, "BTC", ThresholdDirection.ABOVE, BigDecimal("5.00"))

        sub.changeStatus(SubscriptionStatus.ACTIVE)
        sub.changeThreshold(BigDecimal("5.0000"))
        sub.changeDirection(ThresholdDirection.ABOVE)
        sub.changeMarketPair(MarketPair.default(Symbol("BTC")))

        assertThat(sub.revision).isEqualTo(1)
    }

    @Test
    fun `market pair 변경은 revision을 증가시킨다`() {
        val sub = NotificationSubscription.create(1L, "BTC", ThresholdDirection.ABOVE, BigDecimal("5.00"))

        sub.changeMarketPair(MarketPair(Symbol("BTC"), Exchange.UPBIT, Exchange.BINANCE))

        assertThat(sub.koreaExchange).isEqualTo(Exchange.UPBIT)
        assertThat(sub.revision).isEqualTo(2)
    }
}
