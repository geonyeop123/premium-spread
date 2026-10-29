package io.premiumspread.domain.ownermarket

import io.premiumspread.domain.exchangerate.ExchangeRateSnapshot
import io.premiumspread.domain.market.MarketPair
import io.premiumspread.domain.ownerops.OwnerOpsAlertContent
import io.premiumspread.domain.premium.PremiumAggregationSnapshot
import io.premiumspread.domain.ticker.Currency
import io.premiumspread.domain.ticker.Symbol
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class OwnerMarketAlertContentTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private val settings = MarketAlertSettings()
    private val pair = MarketPair.default(Symbol("BTC"))
    private val at = Instant.parse("2026-10-29T00:00:00Z")
    private val observed = Instant.parse("2026-10-28T23:59:55Z")
    private val runbook = "조치 안내: docs/runbooks/owner-market-alert.md"

    // 메일 {t(x)} / Discord {st(x)} 표기
    private val mailAt = "2026-10-29 09:00:00 (2026-10-29T00:00:00Z)"
    private val mailObserved = "2026-10-29 08:59:55 (2026-10-28T23:59:55Z)"
    private val discordAt = "2026-10-29 09:00 KST"
    private val discordObserved = "2026-10-29 08:59 KST"

    private fun bd(value: String) = BigDecimal(value)

    private val swingUp = MarketNotice.Swing(
        pair, at, MarketDirection.UP, Duration.ofMinutes(10), bd("2.00"), bd("2.60"), observed, bd("0.60"),
    )
    private val swingDown = MarketNotice.Swing(
        pair, at, MarketDirection.DOWN, Duration.ofMinutes(10), bd("2.60"), bd("2.00"), observed, bd("-0.60"),
    )
    private val upper = MarketNotice.ThresholdCrossed(
        pair, at, ThresholdLine.UPPER, bd("3.0"), bd("0.1"), bd("3.05"), observed,
    )
    private val lower = MarketNotice.ThresholdCrossed(
        pair, at, ThresholdLine.LOWER, bd("-1.0"), bd("0.1"), bd("-1.04"), observed,
    )
    private val dayHigh = MarketNotice.NewExtreme(
        pair, at, ExtremeWindow.ONE_DAY, MarketDirection.UP, bd("2.50"), bd("2.70"), observed,
    )
    private val hourLow = MarketNotice.NewExtreme(
        pair, at, ExtremeWindow.ONE_HOUR, MarketDirection.DOWN, bd("1.50"), bd("1.35"), observed,
    )

    private val day = PremiumAggregationSnapshot(
        pair = pair,
        high = bd("2.80"),
        low = bd("1.90"),
        open = bd("2.10"),
        close = bd("2.50"),
        avg = bd("2.35"),
        count = 1440,
        observedAt = Instant.parse("2026-10-28T14:59:00Z"),
        fxRate = bd("1385.2"),
    )
    private val fx = ExchangeRateSnapshot("USD", "KRW", bd("1390.5"), Instant.parse("2026-10-28T23:59:30Z"))
    private val value = SummaryPnl.Value(bd("1.2345"), bd("123456.7"), Instant.parse("2026-10-28T23:58:00Z"))

    private fun summary(day: PremiumAggregationSnapshot?, fx: ExchangeRateSnapshot?, pnl: SummaryPnl) =
        MarketNotice.DailySummary(
            pair, at, LocalDate.parse("2026-10-28"), day, Currency.USD, Currency.KRW, fx, pnl,
        )

    private val full = summary(day, fx, value)
    private val empty = summary(null, null, SummaryPnl.Unavailable)
    private val noPosition = summary(day, fx, SummaryPnl.NoPosition(Instant.parse("2026-10-28T23:58:00Z")))

    private val all = listOf(swingUp, swingDown, upper, lower, dayHigh, hourLow, full, empty, noPosition)

    private fun mail(notice: MarketNotice) = OwnerMarketAlertContent.mail(notice, settings, zone)
    private fun discord(notice: MarketNotice) = OwnerMarketAlertContent.discord(notice, settings, zone)

    private fun lines(vararg lines: String) = lines.joinToString("\n")

    private val swingBasis = "기준: 10분 안에서 ±0.5%p 이상 · 같은 방향은 30분 동안 다시 알리지 않습니다."
    private val hysteresis = "기준선 안쪽으로 0.1%p 이상 돌아온 뒤 다시 넘으면 다시 알립니다."
    private val summaryTitle = "김프 일일 요약 BTC BITHUMB/BINANCE 2026-10-28"
    private val summaryDay = "대상일: 2026-10-28 00:00 ~ 24:00 (Asia/Seoul)"
    private val summaryOhlc = "김프 시가 +2.10% · 고가 +2.80% · 저가 +1.90% · 종가 +2.50% · 평균 +2.35%"
    private val unavailable = "실계정 순수익률: 없음 (PnL 모니터 꺼짐 또는 최근 10분 안에 계산 없음)"

    @Test
    fun `급등 메일`() {
        val mail = mail(swingUp)
        assertThat(mail.eventKey).isEqualTo("owner-market.btc-bithumb-binance.swing-up.1793232000000")
        assertThat(mail.subject).isEqualTo("[시장] 김프 급등 BTC BITHUMB/BINANCE +0.60%p (10분)")
        assertThat(mail.body).isEqualTo(
            lines(
                "현재 김프: +2.60% ($mailObserved)",
                "최근 10분 분봉 종가 저점: +2.00%",
                "변화: +0.60%p",
                swingBasis,
                "알림 시각: $mailAt",
                runbook,
            ),
        )
    }

    @Test
    fun `급락 메일`() {
        val mail = mail(swingDown)
        assertThat(mail.eventKey).isEqualTo("owner-market.btc-bithumb-binance.swing-down.1793232000000")
        assertThat(mail.subject).isEqualTo("[시장] 김프 급락 BTC BITHUMB/BINANCE -0.60%p (10분)")
        assertThat(mail.body).isEqualTo(
            lines(
                "현재 김프: +2.00% ($mailObserved)",
                "최근 10분 분봉 종가 고점: +2.60%",
                "변화: -0.60%p",
                swingBasis,
                "알림 시각: $mailAt",
                runbook,
            ),
        )
    }

    @Test
    fun `상단 돌파 메일`() {
        val mail = mail(upper)
        assertThat(mail.eventKey).isEqualTo("owner-market.btc-bithumb-binance.threshold-upper.1793232000000")
        assertThat(mail.subject).isEqualTo("[시장] 김프 상단 기준선 돌파 BTC BITHUMB/BINANCE +3.05% ≥ +3.00%")
        assertThat(mail.body).isEqualTo(
            lines(
                "현재 김프: +3.05% ($mailObserved)",
                "상단 기준선: +3.00%",
                hysteresis,
                "알림 시각: $mailAt",
                runbook,
            ),
        )
    }

    @Test
    fun `하단 이탈 메일`() {
        val mail = mail(lower)
        assertThat(mail.eventKey).isEqualTo("owner-market.btc-bithumb-binance.threshold-lower.1793232000000")
        assertThat(mail.subject).isEqualTo("[시장] 김프 하단 기준선 이탈 BTC BITHUMB/BINANCE -1.04% ≤ -1.00%")
        assertThat(mail.body).isEqualTo(
            lines(
                "현재 김프: -1.04% ($mailObserved)",
                "하단 기준선: -1.00%",
                hysteresis,
                "알림 시각: $mailAt",
                runbook,
            ),
        )
    }

    @Test
    fun `24시간 신고가 메일`() {
        val mail = mail(dayHigh)
        assertThat(mail.eventKey).isEqualTo("owner-market.btc-bithumb-binance.extreme-24h-high.1793232000000")
        assertThat(mail.subject).isEqualTo("[시장] 김프 24시간 신고가 BTC BITHUMB/BINANCE +2.70%")
        assertThat(mail.body).isEqualTo(
            lines(
                "현재 김프: +2.70% ($mailObserved)",
                "직전 24시간 고점: +2.50% (차이 +0.20%p)",
                "기준: 최소 0.1%p · 같은 창·방향은 2시간 동안 다시 알리지 않습니다.",
                "알림 시각: $mailAt",
                runbook,
            ),
        )
    }

    @Test
    fun `1시간 신저가 메일`() {
        val mail = mail(hourLow)
        assertThat(mail.eventKey).isEqualTo("owner-market.btc-bithumb-binance.extreme-1h-low.1793232000000")
        assertThat(mail.subject).isEqualTo("[시장] 김프 1시간 신저가 BTC BITHUMB/BINANCE +1.35%")
        assertThat(mail.body).isEqualTo(
            lines(
                "현재 김프: +1.35% ($mailObserved)",
                "직전 1시간 저점: +1.50% (차이 -0.15%p)",
                "기준: 최소 0.1%p · 같은 창·방향은 30분 동안 다시 알리지 않습니다.",
                "알림 시각: $mailAt",
                runbook,
            ),
        )
    }

    @Test
    fun `요약 메일 - 전부 있음`() {
        val mail = mail(full)
        assertThat(mail.eventKey).isEqualTo("owner-market.btc-bithumb-binance.daily-summary.2026-10-28")
        assertThat(mail.subject).isEqualTo("[시장] $summaryTitle")
        assertThat(mail.body).isEqualTo(
            lines(
                summaryDay,
                summaryOhlc,
                "환율(USD/KRW) 전일 마감: 1,385.20",
                "환율(USD/KRW) 현재: 1,390.50 (2026-10-29 08:59:30 (2026-10-28T23:59:30Z))",
                "실계정 순수익률: +1.23% (+123,457원) · 계산 2026-10-29 08:58:00 (2026-10-28T23:58:00Z)",
                "요약 시각: $mailAt",
                runbook,
            ),
        )
    }

    @Test
    fun `요약 메일 - 일 집계 없음 fx 없음 Unavailable`() {
        val mail = mail(empty)
        assertThat(mail.subject).isEqualTo("[시장] $summaryTitle")
        assertThat(mail.body).isEqualTo(
            lines(
                summaryDay,
                "김프: 전일 집계 없음",
                "환율(USD/KRW) 현재: 없음",
                unavailable,
                "요약 시각: $mailAt",
                runbook,
            ),
        )
    }

    @Test
    fun `요약 메일 - 포지션 없음`() {
        assertThat(mail(noPosition).body).isEqualTo(
            lines(
                summaryDay,
                summaryOhlc,
                "환율(USD/KRW) 전일 마감: 1,385.20",
                "환율(USD/KRW) 현재: 1,390.50 (2026-10-29 08:59:30 (2026-10-28T23:59:30Z))",
                "실계정 순수익률: 포지션 없음 · 계산 2026-10-29 08:58:00 (2026-10-28T23:58:00Z)",
                "요약 시각: $mailAt",
                runbook,
            ),
        )
    }

    @Test
    fun `일 집계의 fxRate 가 없으면 전일 마감 줄이 없음으로 대체된다`() {
        val notice = summary(day.copy(fxRate = null), fx, SummaryPnl.Unavailable)
        assertThat(mail(notice).body.lines()[2]).isEqualTo("환율(USD/KRW) 전일 마감: 없음")
    }

    private fun assertDiscord(notice: MarketNotice, title: String, color: Int, description: String) {
        val message = discord(notice)
        assertThat(message.title).isEqualTo(title)
        assertThat(message.color).isEqualTo(color)
        assertThat(message.description).isEqualTo(description)
    }

    @Test
    fun `급등 Discord`() = assertDiscord(
        swingUp,
        "📈 김프 급등 BTC BITHUMB/BINANCE +0.60%p (10분)",
        15158332,
        lines(
            "현재 김프: +2.60% ($discordObserved)",
            "최근 10분 분봉 종가 저점: +2.00%",
            "변화: +0.60%p",
            swingBasis,
            "알림 시각: $discordAt",
            runbook,
        ),
    )

    @Test
    fun `급락 Discord`() = assertDiscord(
        swingDown,
        "📉 김프 급락 BTC BITHUMB/BINANCE -0.60%p (10분)",
        3447003,
        lines(
            "현재 김프: +2.00% ($discordObserved)",
            "최근 10분 분봉 종가 고점: +2.60%",
            "변화: -0.60%p",
            swingBasis,
            "알림 시각: $discordAt",
            runbook,
        ),
    )

    @Test
    fun `상단 돌파 Discord`() = assertDiscord(
        upper,
        "🔺 김프 상단 기준선 돌파 BTC BITHUMB/BINANCE +3.05% ≥ +3.00%",
        15158332,
        lines(
            "현재 김프: +3.05% ($discordObserved)",
            "상단 기준선: +3.00%",
            hysteresis,
            "알림 시각: $discordAt",
            runbook,
        ),
    )

    @Test
    fun `하단 이탈 Discord`() = assertDiscord(
        lower,
        "🔻 김프 하단 기준선 이탈 BTC BITHUMB/BINANCE -1.04% ≤ -1.00%",
        3447003,
        lines(
            "현재 김프: -1.04% ($discordObserved)",
            "하단 기준선: -1.00%",
            hysteresis,
            "알림 시각: $discordAt",
            runbook,
        ),
    )

    @Test
    fun `24시간 신고가 Discord`() = assertDiscord(
        dayHigh,
        "⬆️ 김프 24시간 신고가 BTC BITHUMB/BINANCE +2.70%",
        15158332,
        lines(
            "현재 김프: +2.70% ($discordObserved)",
            "직전 24시간 고점: +2.50% (차이 +0.20%p)",
            "기준: 최소 0.1%p · 같은 창·방향은 2시간 동안 다시 알리지 않습니다.",
            "알림 시각: $discordAt",
            runbook,
        ),
    )

    @Test
    fun `1시간 신저가 Discord`() = assertDiscord(
        hourLow,
        "⬇️ 김프 1시간 신저가 BTC BITHUMB/BINANCE +1.35%",
        3447003,
        lines(
            "현재 김프: +1.35% ($discordObserved)",
            "직전 1시간 저점: +1.50% (차이 -0.15%p)",
            "기준: 최소 0.1%p · 같은 창·방향은 30분 동안 다시 알리지 않습니다.",
            "알림 시각: $discordAt",
            runbook,
        ),
    )

    @Test
    fun `요약 Discord - 전부 있음`() = assertDiscord(
        full,
        "🗓️ $summaryTitle",
        9807270,
        lines(
            summaryDay,
            summaryOhlc,
            "환율(USD/KRW) 전일 마감: 1,385.20",
            "환율(USD/KRW) 현재: 1,390.50 (2026-10-29 08:59 KST)",
            "실계정 순수익률: +1.23% (+123,457원) · 계산 2026-10-29 08:58 KST",
            "요약 시각: $discordAt",
            runbook,
        ),
    )

    @Test
    fun `요약 Discord - 일 집계 없음 fx 없음 Unavailable`() = assertDiscord(
        empty,
        "🗓️ $summaryTitle",
        9807270,
        lines(
            summaryDay,
            "김프: 전일 집계 없음",
            "환율(USD/KRW) 현재: 없음",
            unavailable,
            "요약 시각: $discordAt",
            runbook,
        ),
    )

    @Test
    fun `요약 Discord - 포지션 없음`() = assertDiscord(
        noPosition,
        "🗓️ $summaryTitle",
        9807270,
        lines(
            summaryDay,
            summaryOhlc,
            "환율(USD/KRW) 전일 마감: 1,385.20",
            "환율(USD/KRW) 현재: 1,390.50 (2026-10-29 08:59 KST)",
            "실계정 순수익률: 포지션 없음 · 계산 2026-10-29 08:58 KST",
            "요약 시각: $discordAt",
            runbook,
        ),
    )

    @Test
    fun `색 상수 정수값`() {
        assertThat(OwnerMarketAlertContent.UP_COLOR).isEqualTo(15158332)
        assertThat(OwnerMarketAlertContent.DOWN_COLOR).isEqualTo(3447003)
        assertThat(OwnerMarketAlertContent.SUMMARY_COLOR).isEqualTo(9807270)
    }

    @Test
    fun `eventKey 는 4형식이고 허용 문자와 길이를 지킨다`() {
        val regex = Regex("[A-Za-z0-9._-]{1,128}")
        for (notice in all) assertThat(regex.matches(OwnerMarketAlertContent.eventKey(notice))).isTrue()
        assertThat(OwnerMarketAlertContent.eventKey(dayHigh)).hasSize(63)
        assertThat(OwnerMarketAlertContent.eventKey(full))
            .isEqualTo("owner-market.btc-bithumb-binance.daily-summary.2026-10-28")
    }

    @Test
    fun `금지 문자열이 어떤 결과에도 없다`() {
        val forbidden = listOf("example.test", "/api/webhooks/", "@", "http", "Exception")
        for (notice in all) {
            val mail = mail(notice)
            val discord = discord(notice)
            val texts = listOf(mail.eventKey, mail.subject, mail.body, discord.title, discord.description)
            for (text in texts) {
                for (word in forbidden) assertThat(text).doesNotContain(word)
            }
        }
    }

    @Test
    fun `급변 기준선 신고가 결과에는 원 금액이 없다`() {
        for (notice in listOf(swingUp, swingDown, upper, lower, dayHigh, hourLow)) {
            val mail = mail(notice)
            val discord = discord(notice)
            for (text in listOf(mail.subject, mail.body, discord.title, discord.description)) {
                assertThat(text).doesNotContain("원")
            }
        }
    }

    @Test
    fun `d 표기는 OwnerOpsAlertContent threshold 와 같다`() {
        assertThat(OwnerOpsAlertContent.threshold(Duration.ofMinutes(30))).isEqualTo("30분")
        assertThat(OwnerOpsAlertContent.threshold(Duration.ofHours(2))).isEqualTo("2시간")
        assertThat(mail(swingUp).body).contains("같은 방향은 ${OwnerOpsAlertContent.threshold(Duration.ofMinutes(30))} 동안")
        assertThat(mail(dayHigh).body).contains("${OwnerOpsAlertContent.threshold(Duration.ofHours(2))} 동안")
    }
}
