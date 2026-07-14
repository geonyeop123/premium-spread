package io.premiumspread.application.notification

import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import io.premiumspread.application.common.ApplicationError
import io.premiumspread.application.common.ApplicationException
import io.premiumspread.domain.notification.NotificationSubscriptionNotFoundException
import io.premiumspread.domain.notification.NotificationSubscriptionService
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class NotificationSubscriptionFacadeTest {
    private val deletedAt = Instant.parse("2026-07-14T03:00:00Z")
    private val service = mockk<NotificationSubscriptionService>()
    private val facade = NotificationSubscriptionFacade(service, Clock.fixed(deletedAt, ZoneOffset.UTC))

    @Test
    fun `삭제 Criteria와 주입 Clock의 시각을 도메인에 전달한다`() {
        justRun { service.delete(10L, 1L, deletedAt) }

        facade.delete(NotificationSubscriptionCriteria.Delete(10L, 1L))

        verify(exactly = 1) { service.delete(10L, 1L, deletedAt) }
    }

    @Test
    fun `잘못된 enum은 DOMAIN_ERROR Application 오류로 변환한다`() {
        assertThatThrownBy {
            facade.create(NotificationSubscriptionCriteria.Create(1L, "BTC", "WRONG", java.math.BigDecimal.ONE))
        }.isInstanceOf(ApplicationException::class.java)
            .hasFieldOrPropertyWithValue("error", ApplicationError.DOMAIN_ERROR)
    }

    @Test
    fun `도메인 미발견 예외는 안정된 Application 오류로 변환한다`() {
        every { service.update(any()) } throws NotificationSubscriptionNotFoundException("internal")

        assertThatThrownBy {
            facade.update(NotificationSubscriptionCriteria.Update(10L, 1L, "ACTIVE", null, null))
        }.isInstanceOf(ApplicationException::class.java)
            .hasFieldOrPropertyWithValue("error", ApplicationError.NOTIFICATION_SUBSCRIPTION_NOT_FOUND)
    }
}
