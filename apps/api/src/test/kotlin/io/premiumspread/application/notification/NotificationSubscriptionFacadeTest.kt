package io.premiumspread.application.notification

import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.premiumspread.application.common.ApplicationError
import io.premiumspread.application.common.ApplicationException
import io.premiumspread.domain.notification.NotificationSubscriptionNotFoundException
import io.premiumspread.domain.notification.NotificationSubscription
import io.premiumspread.domain.notification.NotificationSubscriptionCommand
import io.premiumspread.domain.notification.NotificationSubscriptionService
import io.premiumspread.domain.market.MarketPair
import io.premiumspread.domain.notification.SubscriptionStatus
import io.premiumspread.domain.notification.ThresholdDirection
import io.premiumspread.domain.ticker.Exchange
import io.premiumspread.domain.ticker.Symbol
import org.assertj.core.api.Assertions.assertThat
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
    fun `거래소 쌍을 도메인 command에 전달하고 결과에 보존한다`() {
        val command = slot<NotificationSubscriptionCommand.Create>()
        val subscription = mockk<NotificationSubscription>()
        every { subscription.id } returns 10L
        every { subscription.memberId } returns 1L
        every { subscription.symbol } returns "BTC"
        every { subscription.direction } returns ThresholdDirection.ABOVE
        every { subscription.threshold } returns java.math.BigDecimal("5")
        every { subscription.status } returns SubscriptionStatus.ACTIVE
        every { subscription.marketPair } returns MarketPair(Symbol("BTC"), Exchange.UPBIT, Exchange.BINANCE)
        every { service.create(capture(command)) } returns subscription

        val result = facade.create(
            NotificationSubscriptionCriteria.Create(
                1L,
                "BTC",
                "ABOVE",
                java.math.BigDecimal("5"),
                "UPBIT",
                "BINANCE",
            ),
        )

        assertThat(command.captured.koreaExchange).isEqualTo(Exchange.UPBIT)
        assertThat(command.captured.foreignExchange).isEqualTo(Exchange.BINANCE)
        assertThat(result.koreaExchange).isEqualTo("UPBIT")
        assertThat(result.foreignExchange).isEqualTo("BINANCE")
    }

    @Test
    fun `도메인 미발견 예외는 안정된 Application 오류로 변환한다`() {
        every { service.update(any()) } throws NotificationSubscriptionNotFoundException("internal")

        assertThatThrownBy {
            facade.update(NotificationSubscriptionCriteria.Update(10L, 1L, "ACTIVE", null, null))
        }.isInstanceOf(ApplicationException::class.java)
            .hasFieldOrPropertyWithValue("error", ApplicationError.NOTIFICATION_SUBSCRIPTION_NOT_FOUND)
    }

    @Test
    fun `수정 거래소는 쌍으로 입력해야 한다`() {
        assertThatThrownBy {
            facade.update(
                NotificationSubscriptionCriteria.Update(
                    10L,
                    1L,
                    null,
                    null,
                    null,
                    koreaExchange = "UPBIT",
                ),
            )
        }.isInstanceOf(ApplicationException::class.java)
            .hasFieldOrPropertyWithValue("error", ApplicationError.DOMAIN_ERROR)
    }

    @Test
    fun `거래소 지역 제약 오류는 DOMAIN_ERROR로 변환한다`() {
        every { service.create(any()) } throws IllegalArgumentException("korea exchange region mismatch")

        assertThatThrownBy {
            facade.create(
                NotificationSubscriptionCriteria.Create(
                    1L,
                    "BTC",
                    "ABOVE",
                    java.math.BigDecimal.ONE,
                    "BINANCE",
                    "BINANCE",
                ),
            )
        }.isInstanceOf(ApplicationException::class.java)
            .hasFieldOrPropertyWithValue("error", ApplicationError.DOMAIN_ERROR)
    }
}
