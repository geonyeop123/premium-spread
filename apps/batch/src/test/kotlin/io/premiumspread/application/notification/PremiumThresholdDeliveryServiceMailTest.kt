package io.premiumspread.application.notification

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
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
import java.time.Instant
import java.time.ZoneOffset

/** 회원 김프 메일 문구 특성화 테스트 — 문구 전환 전 현재 동작을 고정한다. */
class PremiumThresholdDeliveryServiceMailTest {
    private val now = Instant.parse("2026-07-15T00:30:00Z")
    private val pair = MarketPair.default(Symbol("BTC"))
    private val subscriptions = mockk<ActiveNotificationSubscriptionPort>()
    private val transactions = mockk<NotificationDeliveryTransactionService>()

    @Test
    fun `ABOVE 구독의 메일 제목과 본문 전문`() {
        val delivery = enqueue(subscription(ThresholdDirection.ABOVE, "5.00"), rate = "6")

        assertThat(delivery.subject).isEqualTo("[premium-spread] BTC 프리미엄 6% 도달")
        assertThat(delivery.payload).isEqualTo(
            listOf(
                "안녕하세요, member님.",
                "",
                "설정하신 알림 조건이 충족되었습니다.",
                "",
                "심볼: BTC",
                "거래소 페어: BTC:BITHUMB:BINANCE",
                "조건: 5% 이상 (ABOVE)",
                "현재 프리미엄: 6%",
                "관측 시각: 2026-07-15T00:30:00Z",
                "",
                "--",
                "premium-spread",
            ).joinToString("\n"),
        )
    }

    @Test
    fun `BELOW 구독의 메일 제목과 본문 전문`() {
        val delivery = enqueue(subscription(ThresholdDirection.BELOW, "-1"), rate = "-1.5")

        assertThat(delivery.subject).isEqualTo("[premium-spread] BTC 프리미엄 -1.5% 도달")
        assertThat(delivery.payload).isEqualTo(
            listOf(
                "안녕하세요, member님.",
                "",
                "설정하신 알림 조건이 충족되었습니다.",
                "",
                "심볼: BTC",
                "거래소 페어: BTC:BITHUMB:BINANCE",
                "조건: -1% 이하 (BELOW)",
                "현재 프리미엄: -1.5%",
                "관측 시각: 2026-07-15T00:30:00Z",
                "",
                "--",
                "premium-spread",
            ).joinToString("\n"),
        )
    }

    private fun enqueue(subscription: ActiveNotificationSubscription, rate: String): NewNotificationDelivery {
        every { subscriptions.findActiveByPair(pair) } returns listOf(subscription)
        val captured = slot<NewNotificationDelivery>()
        every { transactions.enqueue(capture(captured)) } returns NotificationDeliveryEnqueueResult.ENQUEUED
        PremiumThresholdDeliveryService(
            subscriptions,
            transactions,
            NotificationDeliveryProperties(retryJitterRatio = 0.0),
            Clock.fixed(now, ZoneOffset.UTC),
        ).evaluate(snapshot(rate))
        return captured.captured
    }

    private fun subscription(direction: ThresholdDirection, threshold: String) = ActiveNotificationSubscription(
        id = 10,
        memberId = 1,
        memberEmail = "member@example.com",
        memberNickname = "member",
        pair = pair,
        revision = 3,
        direction = direction,
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
