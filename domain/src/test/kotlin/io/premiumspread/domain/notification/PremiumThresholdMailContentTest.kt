package io.premiumspread.domain.notification

import io.premiumspread.domain.market.MarketPair
import io.premiumspread.domain.premium.PremiumSnapshot
import io.premiumspread.domain.ticker.Exchange
import io.premiumspread.domain.ticker.Symbol
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId

/** 문구 정본 message-spec.md "회원 알림". */
class PremiumThresholdMailContentTest {
    private val observedAt = Instant.parse("2026-07-15T00:30:00Z")
    private val seoul = ZoneId.of("Asia/Seoul")
    private val pair = MarketPair.default(Symbol("BTC"))

    private fun subscription(direction: ThresholdDirection, threshold: String, subscribed: MarketPair = pair) =
        ActiveNotificationSubscription(
            id = 10,
            memberId = 1,
            memberEmail = "member@example.com",
            memberNickname = "member",
            pair = subscribed,
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
        observedAt = observedAt,
    )

    @Test
    fun `ABOVE 임계값 도달 메일 전문`() {
        val mail = PremiumThresholdMailContent.render(subscription(ThresholdDirection.ABOVE, "5.00"), snapshot("6"), seoul)

        assertThat(mail.subject).isEqualTo("[김프 알림] BTC 김프 6.00% 도달")
        assertThat(mail.body()).isEqualTo(
            """
            안녕하세요, member님.
            설정하신 김프 알림 조건에 도달했습니다.

            종목       BTC (빗썸 / 바이낸스)
            조건       김프 5.00% 이상
            현재 김프  6.00%
            관측 시각  2026-07-15 09:30 KST

            --
            premium-spread
            """.trimIndent(),
        )
    }

    @Test
    fun `BELOW 임계값 도달 메일 전문`() {
        val mail = PremiumThresholdMailContent.render(subscription(ThresholdDirection.BELOW, "-1"), snapshot("-1.5"), seoul)

        assertThat(mail.subject).isEqualTo("[김프 알림] BTC 김프 -1.50% 도달")
        assertThat(mail.body()).isEqualTo(
            """
            안녕하세요, member님.
            설정하신 김프 알림 조건에 도달했습니다.

            종목       BTC (빗썸 / 바이낸스)
            조건       김프 -1.00% 이하
            현재 김프  -1.50%
            관측 시각  2026-07-15 09:30 KST

            --
            premium-spread
            """.trimIndent(),
        )
    }

    @Test
    fun `관측 시각은 주입한 zone 으로 찍고 UTC 원문이 없다`() {
        val mail = PremiumThresholdMailContent.render(subscription(ThresholdDirection.ABOVE, "5"), snapshot("6"), ZoneId.of("UTC"))

        assertThat(mail.body()).contains("관측 시각  2026-07-15 00:30 UTC").doesNotContain("T00:30")
    }

    @Test
    fun `거래소 표기는 구독 pair 에서 오고 닉네임은 본문 첫 줄에만 있다`() {
        val upbit = MarketPair(Symbol("BTC"), Exchange.UPBIT, Exchange.BINANCE)
        val mail = PremiumThresholdMailContent.render(subscription(ThresholdDirection.ABOVE, "5", upbit), snapshot("6"), seoul)

        assertThat(mail.body()).contains("BTC (업비트 / 바이낸스)").doesNotContain("빗썸")
        assertThat(mail.body().lines().count { it.contains("member") }).isEqualTo(1)
        assertThat(mail.body().lines().first()).contains("member")
        assertThat(mail.subject).doesNotContain("member")
    }
}
