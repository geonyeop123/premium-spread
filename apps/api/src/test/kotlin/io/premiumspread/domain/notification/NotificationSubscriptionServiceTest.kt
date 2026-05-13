package io.premiumspread.domain.notification

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class NotificationSubscriptionServiceTest {

    private val repository = mockk<NotificationSubscriptionRepository>(relaxed = true)
    private val sut = NotificationSubscriptionService(repository)

    @Test
    fun `create는 ACTIVE 상태로 저장한다`() {
        val saved = NotificationSubscription.create(1L, "BTC", ThresholdDirection.ABOVE, BigDecimal("5.00"))
        every { repository.save(any()) } returns saved

        val result = sut.create(
            NotificationSubscriptionCommand.Create(
                memberId = 1L,
                symbol = "btc",
                direction = ThresholdDirection.ABOVE,
                threshold = BigDecimal("5.00"),
            ),
        )

        assertThat(result.status).isEqualTo(SubscriptionStatus.ACTIVE)
        verify(exactly = 1) { repository.save(any()) }
    }

    @Test
    fun `findByIdAndMemberId는 본인 구독을 반환한다`() {
        val sub = NotificationSubscription.create(1L, "BTC", ThresholdDirection.ABOVE, BigDecimal("5.00"))
        every { repository.findById(10L) } returns sub

        val result = sut.findByIdAndMemberId(10L, 1L)
        assertThat(result).isSameAs(sub)
    }

    @Test
    fun `findByIdAndMemberId는 다른 회원 구독이면 null을 반환한다`() {
        val sub = NotificationSubscription.create(1L, "BTC", ThresholdDirection.ABOVE, BigDecimal("5.00"))
        every { repository.findById(10L) } returns sub

        val result = sut.findByIdAndMemberId(10L, 2L)
        assertThat(result).isNull()
    }

    @Test
    fun `findByIdAndMemberId는 존재하지 않으면 null을 반환한다`() {
        every { repository.findById(10L) } returns null
        val result = sut.findByIdAndMemberId(10L, 1L)
        assertThat(result).isNull()
    }

    @Test
    fun `update는 동일 인스턴스의 status, direction, threshold를 부분 갱신한다 (새 인스턴스 생성 금지)`() {
        val sub = NotificationSubscription.create(1L, "BTC", ThresholdDirection.ABOVE, BigDecimal("5.00"))
        every { repository.findById(10L) } returns sub
        every { repository.save(sub) } returns sub

        val result = sut.update(
            NotificationSubscriptionCommand.Update(
                id = 10L,
                memberId = 1L,
                status = SubscriptionStatus.INACTIVE,
                direction = ThresholdDirection.BELOW,
                threshold = BigDecimal("-2.00"),
            ),
        )

        assertThat(result).isSameAs(sub)
        assertThat(result.status).isEqualTo(SubscriptionStatus.INACTIVE)
        assertThat(result.direction).isEqualTo(ThresholdDirection.BELOW)
        assertThat(result.threshold).isEqualByComparingTo("-2.00")
        verify(exactly = 1) { repository.save(sub) }
    }

    @Test
    fun `update는 본인 구독이 아니면 NotFound 예외를 던진다`() {
        val sub = NotificationSubscription.create(1L, "BTC", ThresholdDirection.ABOVE, BigDecimal("5.00"))
        every { repository.findById(10L) } returns sub

        assertThatThrownBy {
            sut.update(NotificationSubscriptionCommand.Update(10L, memberId = 2L, null, null, null))
        }.isInstanceOf(NotificationSubscriptionNotFoundException::class.java)
    }

    @Test
    fun `delete는 본인 구독을 삭제한다`() {
        val sub = NotificationSubscription.create(1L, "BTC", ThresholdDirection.ABOVE, BigDecimal("5.00"))
        every { repository.findById(10L) } returns sub

        sut.delete(10L, memberId = 1L)
        verify(exactly = 1) { repository.delete(sub) }
    }

    @Test
    fun `delete는 본인 구독이 아니면 NotFound를 던진다`() {
        val sub = NotificationSubscription.create(1L, "BTC", ThresholdDirection.ABOVE, BigDecimal("5.00"))
        every { repository.findById(10L) } returns sub

        assertThatThrownBy { sut.delete(10L, memberId = 2L) }
            .isInstanceOf(NotificationSubscriptionNotFoundException::class.java)
    }
}
