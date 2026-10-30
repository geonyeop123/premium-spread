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
import java.time.ZoneId
import java.time.ZoneOffset

/** 회원 김프 메일 문구 특성화 테스트 — 문구 정본 message-spec.md "회원 알림" — 서비스가 값을 payload 로 넘기는 경로를 고정한다. */
class PremiumThresholdDeliveryServiceMailTest {
    private val now = Instant.parse("2026-07-15T00:30:00Z")
    private val pair = MarketPair.default(Symbol("BTC"))
    private val subscriptions = mockk<ActiveNotificationSubscriptionPort>()
    private val transactions = mockk<NotificationDeliveryTransactionService>()

    @Test
    fun `ABOVE 구독의 메일 제목과 본문 전문`() {
        val delivery = enqueue(subscription(ThresholdDirection.ABOVE, "5.00"), rate = "6")

        assertThat(delivery.subject).isEqualTo("[김프 알림] BTC 김프 6.00% 도달")
        assertThat(delivery.payload).isEqualTo(
            listOf(
                "안녕하세요, member님.",
                "설정하신 김프 알림 조건에 도달했습니다.",
                "",
                "종목       BTC (빗썸 / 바이낸스)",
                "조건       김프 5.00% 이상",
                "현재 김프  6.00%",
                "관측 시각  2026-07-15 09:30 KST",
                "",
                "--",
                "premium-spread",
            ).joinToString("\n"),
        )
    }

    @Test
    fun `BELOW 구독의 메일 제목과 본문 전문`() {
        val delivery = enqueue(subscription(ThresholdDirection.BELOW, "-1"), rate = "-1.5")

        assertThat(delivery.subject).isEqualTo("[김프 알림] BTC 김프 -1.50% 도달")
        assertThat(delivery.payload).isEqualTo(
            listOf(
                "안녕하세요, member님.",
                "설정하신 김프 알림 조건에 도달했습니다.",
                "",
                "종목       BTC (빗썸 / 바이낸스)",
                "조건       김프 -1.00% 이하",
                "현재 김프  -1.50%",
                "관측 시각  2026-07-15 09:30 KST",
                "",
                "--",
                "premium-spread",
            ).joinToString("\n"),
        )
    }

    @Test
    fun `서비스에 주입한 zone 으로 관측 시각을 찍는다`() {
        val delivery = enqueue(subscription(ThresholdDirection.ABOVE, "5.00"), rate = "6", zone = ZoneId.of("UTC"))

        assertThat(delivery.payload).contains("관측 시각  2026-07-15 00:30 UTC")
    }

    private fun enqueue(
        subscription: ActiveNotificationSubscription,
        rate: String,
        zone: ZoneId? = null,
    ): NewNotificationDelivery {
        every { subscriptions.findActiveByPair(pair) } returns listOf(subscription)
        val captured = slot<NewNotificationDelivery>()
        every { transactions.enqueue(capture(captured)) } returns NotificationDeliveryEnqueueResult.ENQUEUED
        val properties = NotificationDeliveryProperties(retryJitterRatio = 0.0)
        val clock = Clock.fixed(now, ZoneOffset.UTC)
        val service = if (zone == null) {
            PremiumThresholdDeliveryService(subscriptions, transactions, properties, clock)
        } else {
            PremiumThresholdDeliveryService(subscriptions, transactions, properties, clock, zone)
        }
        service.evaluate(snapshot(rate))
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
