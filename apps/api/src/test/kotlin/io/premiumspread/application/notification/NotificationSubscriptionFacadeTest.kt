package io.premiumspread.application.notification

import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import io.premiumspread.domain.notification.NotificationSubscriptionService
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class NotificationSubscriptionFacadeTest {
    @Test
    fun `구독 삭제 시 주입된 Clock의 시각을 도메인에 전달한다`() {
        val deletedAt = Instant.parse("2026-07-14T03:00:00Z")
        val service = mockk<NotificationSubscriptionService>()
        val facade = NotificationSubscriptionFacade(
            service = service,
            clock = Clock.fixed(deletedAt, ZoneOffset.UTC),
        )
        justRun { service.delete(10L, 1L, deletedAt) }

        facade.delete(10L, 1L)

        verify(exactly = 1) { service.delete(10L, 1L, deletedAt) }
    }
}
