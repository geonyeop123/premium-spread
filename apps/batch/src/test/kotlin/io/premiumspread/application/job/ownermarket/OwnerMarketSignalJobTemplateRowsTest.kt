package io.premiumspread.application.job.ownermarket

import io.mockk.every
import io.mockk.mockk
import io.premiumspread.application.common.JobExecutor
import io.premiumspread.application.common.JobResult
import io.premiumspread.application.notification.owneralert.MutableClock
import io.premiumspread.domain.accountpnl.AccountPnlLatest
import io.premiumspread.domain.aggregation.AggregationWindow
import io.premiumspread.domain.alertmessage.OwnerAlertMessage
import io.premiumspread.domain.batch.BatchMarket
import io.premiumspread.domain.exchangerate.ExchangeRateSnapshot
import io.premiumspread.domain.market.MarketPair
import io.premiumspread.domain.owneralert.OwnerAlertChannel
import io.premiumspread.domain.owneralert.OwnerAlertChannels
import io.premiumspread.domain.owneralert.OwnerAlertKind
import io.premiumspread.domain.owneralert.OwnerAlertRouteOverride
import io.premiumspread.domain.owneralert.OwnerAlertRouting
import io.premiumspread.domain.ownermarket.MarketAlertSettings
import io.premiumspread.domain.ownermarket.MarketDirection
import io.premiumspread.domain.ownermarket.MarketNotice
import io.premiumspread.domain.ownermarket.MarketWindows
import io.premiumspread.domain.ownermarket.OwnerMarketAlertContent
import io.premiumspread.domain.ownermarket.SummaryPnl
import io.premiumspread.domain.premium.PremiumAggregationSnapshot
import io.premiumspread.domain.premium.PremiumSnapshot
import io.premiumspread.domain.ticker.Currency
import io.premiumspread.domain.ticker.Quote
import io.premiumspread.domain.ticker.Symbol
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Job 이 만드는 행이 템플릿 결과와 같다 (dod AC15(b)). 모든 채널을 켠 라우팅 + 해당 종류 메일 override 로
 * EMAIL·DISCORD 2행이 생기고, DISCORD 행은 layout 을 갖고, EMAIL 본문은 같은 입력의 템플릿 `mail.body()` 와 같다.
 * fake 구성은 같은 디렉터리 `OwnerMarketSignalJobTest` 의 복제다(R3 — 기존 파일 비의존).
 */
class OwnerMarketSignalJobTemplateRowsTest {
    private val zone = ZoneId.of("Asia/Seoul")

    /** 2026-10-29 06:00 KST — 요약 시각(09시) 전이다. */
    private val start = Instant.parse("2026-10-28T21:00:00Z")
    private val clock = MutableClock(start)
    private val btc = Symbol("BTC")
    private val pair = MarketPair.default(btc)
    private val settings = MarketAlertSettings()
    private val rate = BigDecimal("1.00")
    private val store = FakeOwnerMarketAlertStore()
    private val executor = mockk<JobExecutor>()
    private val premiums = FakePremiumRead { snapshot(BigDecimal("1.60")) }
    private val aggregations = FakeAggregationRead(
        minutes = { buckets(MarketWindows.of(clock.instant(), zone).minutes, Duration.ofMinutes(1), PARTIAL_MINUTES) },
        hours = { buckets(MarketWindows.of(clock.instant(), zone).hours, Duration.ofHours(1), null) },
        day = { listOf(bucket(MarketWindows.of(clock.instant(), zone).previousDay.from)) },
    )
    private val fx = FakeFxRead { ExchangeRateSnapshot("USD", "KRW", BigDecimal("1385.20"), clock.instant()) }
    private val pnl = FakeAccountPnlLatest { AccountPnlLatest(false, null, null, clock.instant()) }

    init {
        every { executor.execute(any(), any()) } answers { secondArg<() -> JobResult>().invoke() }
    }

    private fun job(kind: OwnerAlertKind) = OwnerMarketSignalJob(
        premiums,
        aggregations,
        fx,
        pnl,
        {
            BatchMarket(
                pair,
                Quote.coin(btc, Currency.KRW),
                Quote.coin(btc, Currency.USD),
                Currency.USD,
                Currency.KRW,
            )
        },
        store,
        OwnerAlertRouting(
            OwnerAlertChannels(email = true, discord = true),
            mapOf(kind to OwnerAlertRouteOverride(email = true)),
        ),
        executor,
        clock,
        settings,
    )

    private fun snapshot(value: BigDecimal) = PremiumSnapshot(
        pair = pair,
        premiumRate = value,
        koreaPrice = BigDecimal("100"),
        foreignPrice = BigDecimal("1"),
        foreignPriceInKrw = BigDecimal("99"),
        fxRate = BigDecimal("1385.20"),
        observedAt = clock.instant().minusSeconds(1),
    )

    private fun bucket(at: Instant) = PremiumAggregationSnapshot(
        pair, rate, rate, rate, rate, rate, 1, at, BigDecimal("1385.20"),
    )

    private fun buckets(window: AggregationWindow, step: Duration, last: Int?): List<PremiumAggregationSnapshot> {
        val all = generateSequence(window.from) { it.plus(step) }.takeWhile { it < window.to }.toList()
        return (if (last == null) all else all.takeLast(last)).map(::bucket)
    }

    @Test
    fun `김프 급등 회차는 EMAIL DISCORD 2행이고 본문과 layout 이 템플릿 결과와 같다`() {
        assertThat(job(OwnerAlertKind.PREMIUM_SWING).run()).isEqualTo(JobResult.Success)

        val notice = MarketNotice.Swing(
            pair,
            start,
            MarketDirection.UP,
            Duration.ofMinutes(10),
            BigDecimal("1.00"),
            BigDecimal("1.60"),
            start.minusSeconds(1),
            BigDecimal("0.60"),
        )
        assertRows(OwnerMarketAlertContent.message(notice, settings, zone), "swing-up")
    }

    @Test
    fun `일일 요약 회차는 EMAIL DISCORD 2행이고 본문과 layout 이 템플릿 결과와 같다`() {
        clock.advance(Duration.ofHours(3))
        premiums.next = { snapshot(rate) }

        assertThat(job(OwnerAlertKind.DAILY_SUMMARY).run()).isEqualTo(JobResult.Success)

        val now = clock.instant()
        val notice = MarketNotice.DailySummary(
            pair,
            now,
            LocalDate.parse("2026-10-28"),
            bucket(MarketWindows.of(now, zone).previousDay.from),
            Currency.USD,
            Currency.KRW,
            ExchangeRateSnapshot("USD", "KRW", BigDecimal("1385.20"), now),
            SummaryPnl.NoPosition(now),
        )
        assertRows(OwnerMarketAlertContent.message(notice, settings, zone), "daily-summary")
    }

    private fun assertRows(message: OwnerAlertMessage, keyPart: String) {
        val rows = store.rows
        assertThat(rows.map { it.channel }).containsExactly(OwnerAlertChannel.EMAIL, OwnerAlertChannel.DISCORD)
        assertThat(rows.map { it.eventKey }.toSet()).containsExactly(message.eventKey)
        assertThat(message.eventKey).contains(keyPart)
        assertThat(rows[0].body).isEqualTo(message.mail.body())
        assertThat(rows[1].discordLayout).isNotNull
        assertThat(rows[1].discordLayout).isEqualTo(message.discord.toDiscordMessage().layout)
    }

    private companion object {
        /** 최근 10분 분봉만 준다 — 모두 rate 값이라 급등 기준(저점)은 1.00 이다. */
        const val PARTIAL_MINUTES = 10
    }
}
