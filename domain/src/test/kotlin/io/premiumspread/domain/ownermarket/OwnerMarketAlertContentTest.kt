package io.premiumspread.domain.ownermarket

import io.premiumspread.domain.alertmessage.EmbedCell
import io.premiumspread.domain.alertmessage.OwnerAlertMessage
import io.premiumspread.domain.exchangerate.ExchangeRateSnapshot
import io.premiumspread.domain.market.MarketPair
import io.premiumspread.domain.premium.PremiumAggregationSnapshot
import io.premiumspread.domain.ticker.Currency
import io.premiumspread.domain.ticker.Exchange
import io.premiumspread.domain.ticker.Symbol
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 문구 정본 message-spec.md "시장 알림". 정렬은 design §3.2 규칙(표시 폭 최댓값 + 2)의 출력이다. */
class OwnerMarketAlertContentTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private val settings = MarketAlertSettings()
    private val pair = MarketPair.default(Symbol("BTC"))
    private val at = Instant.parse("2026-10-29T00:00:00Z")
    private val observed = Instant.parse("2026-10-28T23:59:00Z")
    private val author = "시장 · BTC 빗썸/바이낸스"
    private val basis = "기준 시각 2026-10-29 09:00 KST"

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
        pair, bd("2.80"), bd("1.90"), bd("2.10"), bd("2.50"), bd("2.35"), 1440,
        Instant.parse("2026-10-27T15:00:00Z"), bd("1385.20"),
    )
    private val fx = ExchangeRateSnapshot("USD", "KRW", bd("1390.50"), Instant.parse("2026-10-28T23:59:00Z"))
    private val pnl = SummaryPnl.Value(bd("1.23"), bd("123456.7"), Instant.parse("2026-10-28T23:58:00Z"))

    private fun summary(
        d: PremiumAggregationSnapshot? = day,
        f: ExchangeRateSnapshot? = fx,
        p: SummaryPnl = pnl,
        base: Currency = Currency.USD,
        quote: Currency = Currency.KRW,
    ) = MarketNotice.DailySummary(pair, at, LocalDate.parse("2026-10-28"), d, base, quote, f, p)

    private fun msg(notice: MarketNotice): OwnerAlertMessage = OwnerMarketAlertContent.message(notice, settings, zone)

    private fun cell(name: String, value: String) = EmbedCell(name, value)

    @Test
    fun `김프 급등 golden`() {
        val m = msg(swingUp)

        assertThat(m.eventKey).isEqualTo("owner-market.btc-bithumb-binance.swing-up.1793232000000")
        assertThat(m.mail.subject).isEqualTo("[시장] 김프 급등 +0.60%p → +2.60% (BTC)")
        assertThat(m.mail.body()).isEqualTo(
            """
            BTC 김프가 10분 사이 0.60%p 올랐습니다. (빗썸/바이낸스)

            현재 김프  +2.60% (08:59 관측)
            10분 저점  +2.00%
            변화       +0.60%p

            기준: 10분 안에서 ±0.5%p 이상. 같은 방향은 30분 동안 다시 알리지 않습니다.

            $basis
            """.trimIndent(),
        )
        val e = m.discord
        assertThat(e.author).isEqualTo(author)
        assertThat(e.title).isEqualTo("📈 김프 급등 +0.60%p → +2.60%")
        assertThat(e.color).isEqualTo(0xE74C3C)
        assertThat(e.footer).isEqualTo("기준 10분 안 ±0.5%p · 같은 방향은 30분 동안 다시 알리지 않음")
        assertThat(e.timestamp).isEqualTo(at)
        assertThat(e.rows).containsExactly(
            listOf(cell("현재 김프", "+2.60%"), cell("10분 저점", "+2.00%"), cell("변화", "+0.60%p")),
        )
    }

    @Test
    fun `김프 급락 golden`() {
        val m = msg(swingDown)

        assertThat(m.mail.subject).isEqualTo("[시장] 김프 급락 -0.60%p → +2.00% (BTC)")
        assertThat(m.mail.body()).isEqualTo(
            """
            BTC 김프가 10분 사이 0.60%p 내렸습니다. (빗썸/바이낸스)

            현재 김프  +2.00% (08:59 관측)
            10분 고점  +2.60%
            변화       -0.60%p

            기준: 10분 안에서 ±0.5%p 이상. 같은 방향은 30분 동안 다시 알리지 않습니다.

            $basis
            """.trimIndent(),
        )
        val e = m.discord
        assertThat(e.title).isEqualTo("📉 김프 급락 -0.60%p → +2.00%")
        assertThat(e.color).isEqualTo(0x3498DB)
        assertThat(e.footer).isEqualTo("기준 10분 안 ±0.5%p · 같은 방향은 30분 동안 다시 알리지 않음")
        assertThat(e.rows).containsExactly(
            listOf(cell("현재 김프", "+2.00%"), cell("10분 고점", "+2.60%"), cell("변화", "-0.60%p")),
        )
    }

    @Test
    fun `김프 상단 기준선 돌파 golden`() {
        val m = msg(upper)

        assertThat(m.eventKey).isEqualTo("owner-market.btc-bithumb-binance.threshold-upper.1793232000000")
        assertThat(m.mail.subject).isEqualTo("[시장] 김프 상단 기준선 돌파 +3.05% (BTC)")
        assertThat(m.mail.body()).isEqualTo(
            """
            BTC 김프가 상단 기준선을 넘었습니다. (빗썸/바이낸스)

            현재 김프    +3.05% (08:59 관측)
            상단 기준선  +3.00%

            기준선 안쪽으로 0.1%p 이상 돌아온 뒤 다시 넘으면 다시 알립니다.

            $basis
            """.trimIndent(),
        )
        val e = m.discord
        assertThat(e.author).isEqualTo(author)
        assertThat(e.title).isEqualTo("🔺 김프 상단 기준선 돌파 +3.05%")
        assertThat(e.color).isEqualTo(0xE74C3C)
        assertThat(e.footer).isEqualTo("기준선 안쪽으로 0.1%p 이상 돌아온 뒤 다시 넘으면 다시 알림")
        assertThat(e.rows).containsExactly(listOf(cell("현재 김프", "+3.05%"), cell("상단 기준선", "+3.00%")))
    }

    @Test
    fun `김프 하단 기준선 이탈 golden`() {
        val m = msg(lower)

        assertThat(m.mail.subject).isEqualTo("[시장] 김프 하단 기준선 이탈 -1.04% (BTC)")
        assertThat(m.mail.body()).isEqualTo(
            """
            BTC 김프가 하단 기준선 아래로 내려갔습니다. (빗썸/바이낸스)

            현재 김프    -1.04% (08:59 관측)
            하단 기준선  -1.00%

            기준선 안쪽으로 0.1%p 이상 돌아온 뒤 다시 넘으면 다시 알립니다.

            $basis
            """.trimIndent(),
        )
        val e = m.discord
        assertThat(e.title).isEqualTo("🔻 김프 하단 기준선 이탈 -1.04%")
        assertThat(e.color).isEqualTo(0x3498DB)
        assertThat(e.rows).containsExactly(listOf(cell("현재 김프", "-1.04%"), cell("하단 기준선", "-1.00%")))
    }

    @Test
    fun `김프 24시간 신고가 golden`() {
        val m = msg(dayHigh)

        assertThat(m.eventKey).isEqualTo("owner-market.btc-bithumb-binance.extreme-24h-high.1793232000000")
        assertThat(m.mail.subject).isEqualTo("[시장] 김프 24시간 신고가 +2.70% (BTC)")
        assertThat(m.mail.body()).isEqualTo(
            """
            BTC 김프가 24시간 고점을 새로 썼습니다. (빗썸/바이낸스)

            현재 김프         +2.70% (08:59 관측)
            직전 24시간 고점  +2.50%
            차이              +0.20%p

            최소 0.1%p 이상일 때만 알리고, 같은 기간·방향은 2시간 동안 다시 알리지 않습니다.

            $basis
            """.trimIndent(),
        )
        val e = m.discord
        assertThat(e.title).isEqualTo("⬆️ 김프 24시간 신고가 +2.70%")
        assertThat(e.color).isEqualTo(0xE74C3C)
        assertThat(e.footer).isEqualTo("최소 0.1%p · 같은 기간·방향은 2시간 동안 다시 알리지 않음")
        assertThat(e.rows).containsExactly(
            listOf(cell("현재 김프", "+2.70%"), cell("직전 24시간 고점", "+2.50%"), cell("차이", "+0.20%p")),
        )
    }

    @Test
    fun `김프 1시간 신저가 golden`() {
        val m = msg(hourLow)

        assertThat(m.eventKey).isEqualTo("owner-market.btc-bithumb-binance.extreme-1h-low.1793232000000")
        assertThat(m.mail.subject).isEqualTo("[시장] 김프 1시간 신저가 +1.35% (BTC)")
        assertThat(m.mail.body()).isEqualTo(
            """
            BTC 김프가 1시간 저점을 새로 썼습니다. (빗썸/바이낸스)

            현재 김프        +1.35% (08:59 관측)
            직전 1시간 저점  +1.50%
            차이             -0.15%p

            최소 0.1%p 이상일 때만 알리고, 같은 기간·방향은 30분 동안 다시 알리지 않습니다.

            $basis
            """.trimIndent(),
        )
        val e = m.discord
        assertThat(e.title).isEqualTo("⬇️ 김프 1시간 신저가 +1.35%")
        assertThat(e.color).isEqualTo(0x3498DB)
        assertThat(e.footer).isEqualTo("최소 0.1%p · 같은 기간·방향은 30분 동안 다시 알리지 않음")
        assertThat(e.rows).containsExactly(
            listOf(cell("현재 김프", "+1.35%"), cell("직전 1시간 저점", "+1.50%"), cell("차이", "-0.15%p")),
        )
    }

    @Test
    fun `김프 일일 요약 golden`() {
        val m = msg(summary())

        assertThat(m.eventKey).isEqualTo("owner-market.btc-bithumb-binance.daily-summary.2026-10-28")
        assertThat(m.mail.subject).isEqualTo("[시장] 김프 일일 요약 10/28 (BTC)")
        assertThat(m.mail.body()).isEqualTo(
            """
            2026-10-28 BTC 김프 요약입니다. (빗썸/바이낸스, 00:00 ~ 24:00 KST)

            ■ 김프
            시가 +2.10% · 고가 +2.80% · 저가 +1.90%
            종가 +2.50% · 평균 +2.35%

            ■ 환율 (USD/KRW)
            전일 마감  1,385.20원
            현재       1,390.50원

            ■ 실계정
            순수익률  +1.23% (+123,457원)

            $basis
            """.trimIndent(),
        )
        val e = m.discord
        assertThat(e.author).isEqualTo(author)
        assertThat(e.title).isEqualTo("🗓️ 김프 일일 요약 10/28")
        assertThat(e.color).isEqualTo(0x95A5A6)
        assertThat(e.footer).isEqualTo("대상 10-28 00:00 ~ 24:00 KST")
        assertThat(e.rows).containsExactly(
            listOf(cell("시가", "+2.10%"), cell("고가", "+2.80%"), cell("저가", "+1.90%")),
            listOf(cell("종가", "+2.50%"), cell("평균", "+2.35%")),
            listOf(cell("환율 전일 마감", "1,385.20원/USD"), cell("환율 현재", "1,390.50원/USD")),
            listOf(cell("실계정 순수익률", "+1.23% (+123,457원)")),
        )
    }

    @Test
    fun `일일 요약 결측은 없음 표기다`() {
        val m = msg(summary(d = null, f = null, p = SummaryPnl.Unavailable))

        assertThat(m.mail.body()).isEqualTo(
            """
            2026-10-28 BTC 김프 요약입니다. (빗썸/바이낸스, 00:00 ~ 24:00 KST)

            ■ 김프
            전일 집계 없음

            ■ 환율 (USD/KRW)
            전일 마감  없음
            현재       없음

            ■ 실계정
            순수익률  없음

            $basis
            """.trimIndent(),
        )
        assertThat(m.discord.rows).containsExactly(
            listOf(cell("김프", "전일 집계 없음")),
            listOf(cell("환율 전일 마감", "없음"), cell("환율 현재", "없음")),
            listOf(cell("실계정 순수익률", "없음")),
        )
    }

    @Test
    fun `일일 요약 NoPosition 은 포지션 없음 이고 KRW 가 quote 가 아니면 통화쌍을 붙인다`() {
        val m = msg(summary(p = SummaryPnl.NoPosition(at), base = Currency.KRW, quote = Currency.USD))

        assertThat(m.mail.body()).contains("■ 환율 (KRW/USD)").contains("전일 마감  1,385.20 USD")
            .contains("순수익률  포지션 없음")
        assertThat(m.discord.rows[2]).containsExactly(
            cell("환율 전일 마감", "1,385.20 USD/KRW"),
            cell("환율 현재", "1,390.50 USD/KRW"),
        )
        assertThat(m.discord.rows[3]).containsExactly(cell("실계정 순수익률", "포지션 없음"))
    }

    @Test
    fun `author 와 메일 거래소쌍은 알림 자신의 pair 에서 온다`() {
        val other = MarketPair(Symbol("BTC"), Exchange.UPBIT, Exchange.BINANCE)
        val m = msg(swingUp.copy(pair = other))

        assertThat(m.discord.author).isEqualTo("시장 · BTC 업비트/바이낸스")
        assertThat(m.mail.body()).contains("(업비트/바이낸스)").doesNotContain("빗썸")
        assertThat(m.eventKey).contains("btc-upbit-binance")
    }

    @Test
    fun `시각은 KST 이고 UTC 원문이 없다`() {
        val all = listOf(swingUp, swingDown, upper, lower, dayHigh, hourLow, summary()).map(::msg)

        all.forEach {
            assertThat(it.mail.body()).doesNotContain("UTC").doesNotContain("Z)").doesNotContain("T00")
            assertThat(it.mail.body()).endsWith(basis)
        }
    }
}
