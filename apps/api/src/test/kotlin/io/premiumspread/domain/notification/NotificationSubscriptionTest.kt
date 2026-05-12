package io.premiumspread.domain.notification

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
    }

    @Test
    fun `changeThreshold는 임계값만 바꾼다`() {
        val sub = NotificationSubscription.create(1L, "BTC", ThresholdDirection.ABOVE, BigDecimal("5.00"))
        sub.changeThreshold(BigDecimal("7.50"))
        assertThat(sub.threshold).isEqualByComparingTo("7.50")
    }

    @Test
    fun `changeDirection은 방향만 바꾼다`() {
        val sub = NotificationSubscription.create(1L, "BTC", ThresholdDirection.ABOVE, BigDecimal("5.00"))
        sub.changeDirection(ThresholdDirection.BELOW)
        assertThat(sub.direction).isEqualTo(ThresholdDirection.BELOW)
    }
}
