package io.premiumspread.application.notification

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.premiumspread.config.NotificationDeliveryProperties
import io.premiumspread.domain.market.MarketPair
import io.premiumspread.domain.notification.ActiveNotificationSubscription
import io.premiumspread.domain.notification.ActiveNotificationSubscriptionPort
import io.premiumspread.domain.notification.NewNotificationDelivery
import io.premiumspread.domain.notification.NotificationDeliveryEnqueueResult
import io.premiumspread.domain.notification.ThresholdDirection
import io.premiumspread.domain.premium.PremiumSnapshot
import io.premiumspread.domain.ticker.Symbol
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class PremiumThresholdDeliveryServiceTest {
    private val now = Instant.parse("2026-07-15T00:30:00Z")
    private val pair = MarketPair.default(Symbol("BTC"))
    private val subscriptions = mockk<ActiveNotificationSubscriptionPort>()
    private val transactions = mockk<NotificationDeliveryTransactionService>()

    @Test
    fun `matching subscription is enqueued with revision pair threshold and cooldown window identity`() {
        val matching = subscription(id = 10, revision = 3, threshold = "5.00")
        val nonMatching = subscription(id = 11, revision = 1, threshold = "8")
        every { subscriptions.findActiveByPair(pair) } returns listOf(matching, nonMatching)
        val delivery = slot<NewNotificationDelivery>()
        every { transactions.enqueue(capture(delivery)) } returns NotificationDeliveryEnqueueResult.ENQUEUED
        val sut = PremiumThresholdDeliveryService(
            subscriptions,
            transactions,
            NotificationDeliveryProperties(retryJitterRatio = 0.0),
            Clock.fixed(now, ZoneOffset.UTC),
        )

        sut.evaluate(snapshot(rate = "6"))

        verify(exactly = 1) { transactions.enqueue(any()) }
        assertThat(delivery.captured.subscriptionId).isEqualTo(10)
        assertThat(delivery.captured.eventKey)
            .isEqualTo(
                "v2|subscriptionId=10|subscriptionRevision=3" +
                    "|marketPair=BTC:BITHUMB:BINANCE|direction=ABOVE|threshold=5" +
                    "|cooldownMillis=3600000|windowStart=2026-07-15T00:00:00Z",
            )
        assertThat(delivery.captured.recipientEmail).isEqualTo("member@example.com")
        assertThat(delivery.captured.nextAttemptAt).isEqualTo(now)
    }

    @Test
    fun `같은 window start라도 cooldown 설정이 바뀌면 다른 event key를 enqueue한다`() {
        every { subscriptions.findActiveByPair(pair) } returns listOf(subscription(10, 3, "5"))
        val deliveries = mutableListOf<NewNotificationDelivery>()
        every { transactions.enqueue(capture(deliveries)) } returns NotificationDeliveryEnqueueResult.ENQUEUED
        val fixedClock = Clock.fixed(now, ZoneOffset.UTC)

        PremiumThresholdDeliveryService(
            subscriptions,
            transactions,
            NotificationDeliveryProperties(cooldownWindow = Duration.ofHours(1), retryJitterRatio = 0.0),
            fixedClock,
        ).evaluate(snapshot(rate = "6"))
        PremiumThresholdDeliveryService(
            subscriptions,
            transactions,
            NotificationDeliveryProperties(cooldownWindow = Duration.ofHours(2), retryJitterRatio = 0.0),
            fixedClock,
        ).evaluate(snapshot(rate = "6"))

        assertThat(deliveries).hasSize(2)
        assertThat(deliveries[0].eventKey).isNotEqualTo(deliveries[1].eventKey)
        assertThat(deliveries[0].eventKey).contains("cooldownMillis=3600000")
        assertThat(deliveries[1].eventKey).contains("cooldownMillis=7200000")
        assertThat(deliveries.map { it.eventKey.substringAfter("|windowStart=") }).containsOnly(
            "2026-07-15T00:00:00Z",
        )
    }

    private fun subscription(id: Long, revision: Long, threshold: String) = ActiveNotificationSubscription(
        id = id,
        memberId = 1,
        memberEmail = "member@example.com",
        memberNickname = "member",
        pair = pair,
        revision = revision,
        direction = ThresholdDirection.ABOVE,
        threshold = BigDecimal(threshold),
    )

    private fun snapshot(rate: String) = PremiumSnapshot(
        pair = pair,
        premiumRate = BigDecimal(rate),
        koreaPrice = BigDecimal("100"),
        foreignPrice = BigDecimal("90"),
        foreignPriceInKrw = BigDecimal("94"),
        fxRate = BigDecimal("1400"),
        observedAt = now,
    )
}
