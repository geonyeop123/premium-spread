package io.premiumspread.domain.ownermarket

import io.premiumspread.domain.accountpnl.AccountPnlLatest
import io.premiumspread.domain.exchangerate.ExchangeRateSnapshot
import io.premiumspread.domain.market.MarketPair
import io.premiumspread.domain.owneralert.OwnerAlertKind
import io.premiumspread.domain.premium.PremiumAggregationSnapshot
import io.premiumspread.domain.ticker.Currency
import io.premiumspread.domain.ticker.Symbol
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class DailySummaryPolicyTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private val now = Instant.parse("2026-10-29T00:00:00Z")
    private val pair = MarketPair.default(Symbol("BTC"))
    private val key = MarketKeys.dailySummary(MarketKeys.pairKey(pair))
    private val settings = MarketAlertSettings()

    private fun state(date: LocalDate?) =
        MarketAlertState(key, OwnerAlertKind.DAILY_SUMMARY, null, null, null, date, 3)

    private fun due(at: String, date: LocalDate? = null, s: MarketAlertSettings = settings) =
        DailySummaryPolicy.due(state(date), s, Instant.parse(at), zone)

    private val day = PremiumAggregationSnapshot(
        pair, BigDecimal("3"), BigDecimal("1"), BigDecimal("2"), BigDecimal("2.5"), BigDecimal("2"), 1440,
        Instant.parse("2026-10-27T15:00:00Z"),
    )
    private val fx = ExchangeRateSnapshot("USD", "KRW", BigDecimal("1400"), now)

    @Test
    fun `a - 09시 전에는 차례가 아니고 09시부터 차례다`() {
        assertThat(due("2026-10-28T23:59:59Z")).isFalse()
        assertThat(due("2026-10-29T00:00:00Z")).isTrue()
    }

    @Test
    fun `a - 오늘 이상의 summaryDate 면 차례가 아니고 전날이면 차례다`() {
        assertThat(due("2026-10-29T00:00:00Z", LocalDate.of(2026, 10, 29))).isFalse()
        assertThat(due("2026-10-29T00:00:00Z", LocalDate.of(2026, 10, 30))).isFalse()
        assertThat(due("2026-10-29T00:00:00Z", LocalDate.of(2026, 10, 28))).isTrue()
    }

    @Test
    fun `a - summaryHour 1 은 01시 KST 부터다`() {
        val s = MarketAlertSettings(summaryHour = 1)

        assertThat(due("2026-10-28T16:00:00Z", s = s)).isTrue()
        assertThat(due("2026-10-28T15:59:59Z", s = s)).isFalse()
    }

    @Test
    fun `a - due 는 key 를 검사하지 않는다`() {
        val other = MarketAlertState.initial(
            MarketKeys.threshold(MarketKeys.pairKey(pair)),
            OwnerAlertKind.PREMIUM_THRESHOLD_CROSSED,
        )

        assertThat(DailySummaryPolicy.due(other, settings, now, zone)).isTrue()
    }

    @Test
    fun `b - decide 는 summaryDate 와 lastNotifiedAt 을 쓰고 입력을 그대로 옮긴다`() {
        val pnl = AccountPnlLatest(true, BigDecimal("1.23"), BigDecimal("4567"), now.minusSeconds(60))
        val input = DailySummaryInput(day, Currency.USD, Currency.KRW, fx, pnl)

        val result = DailySummaryPolicy.decide(state(LocalDate.of(2026, 10, 27)), pair, input, settings, now, zone)

        assertThat(result.next).containsExactly(
            MarketAlertState(key, OwnerAlertKind.DAILY_SUMMARY, now, null, null, LocalDate.of(2026, 10, 29), 3),
        )
        assertThat(result.notices).containsExactly(
            MarketNotice.DailySummary(
                pair, now, LocalDate.of(2026, 10, 28), day, Currency.USD, Currency.KRW, fx,
                SummaryPnl.Value(BigDecimal("1.23"), BigDecimal("4567"), now.minusSeconds(60)),
            ),
        )
    }

    @Test
    fun `b - day 와 fx 가 없어도 요약하고 fx 라벨은 input 값이다`() {
        val input = DailySummaryInput(null, Currency.USD, Currency.KRW, null, null)

        val notice = DailySummaryPolicy.decide(state(null), pair, input, settings, now, zone)
            .notices.single() as MarketNotice.DailySummary

        assertThat(notice.day).isNull()
        assertThat(notice.fx).isNull()
        assertThat(notice.fxBase).isEqualTo(Currency.USD)
        assertThat(notice.fxQuote).isEqualTo(Currency.KRW)
        assertThat(notice.pnl).isEqualTo(SummaryPnl.Unavailable)
    }

    @Test
    fun `c - summaryPnl 네 분기`() {
        val maxAge = settings.summaryPnlMaxAge
        val value = { age: Duration ->
            AccountPnlLatest(true, BigDecimal("1.23"), BigDecimal("4567"), now.minus(age))
        }

        assertThat(DailySummaryPolicy.summaryPnl(null, settings, now)).isEqualTo(SummaryPnl.Unavailable)
        assertThat(DailySummaryPolicy.summaryPnl(value(maxAge), settings, now))
            .isEqualTo(SummaryPnl.Value(BigDecimal("1.23"), BigDecimal("4567"), now.minus(maxAge)))
        assertThat(DailySummaryPolicy.summaryPnl(value(maxAge.plusMillis(1)), settings, now))
            .isEqualTo(SummaryPnl.Unavailable)
        assertThat(DailySummaryPolicy.summaryPnl(AccountPnlLatest.noPosition(now.minusSeconds(5)), settings, now))
            .isEqualTo(SummaryPnl.NoPosition(now.minusSeconds(5)))
    }

    @Test
    fun `c - 미래 calculatedAt 은 나이 0 으로 유효하다`() {
        val future = AccountPnlLatest(true, BigDecimal("1.23"), BigDecimal("4567"), now.plusSeconds(30))

        assertThat(DailySummaryPolicy.summaryPnl(future, settings, now)).isInstanceOf(SummaryPnl.Value::class.java)
    }

    @Test
    fun `c - 오래된 position false 는 NoPosition 이 아니라 Unavailable 이다`() {
        val old = AccountPnlLatest.noPosition(now.minus(settings.summaryPnlMaxAge).minusMillis(1))

        assertThat(DailySummaryPolicy.summaryPnl(old, settings, now)).isEqualTo(SummaryPnl.Unavailable)
    }

    @Test
    fun `e - daily-summary 가 아닌 key 로 decide 하면 예외다`() {
        val swing = MarketAlertState.initial(
            MarketKeys.swing(MarketKeys.pairKey(pair), MarketDirection.UP),
            OwnerAlertKind.PREMIUM_SWING,
        )
        val input = DailySummaryInput(null, Currency.USD, Currency.KRW, null, null)

        assertThatThrownBy { DailySummaryPolicy.decide(swing, pair, input, settings, now, zone) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("market state key mismatch.")
    }
}
