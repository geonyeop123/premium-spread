package io.premiumspread.application.job.accountpnl

import io.mockk.every
import io.mockk.mockk
import io.premiumspread.application.common.JobExecutor
import io.premiumspread.application.common.JobResult
import io.premiumspread.application.notification.owneralert.FakeDiscordSender
import io.premiumspread.application.notification.owneralert.FakeMailSender
import io.premiumspread.application.notification.owneralert.FakeOwnerAlertStore
import io.premiumspread.application.notification.owneralert.MutableClock
import io.premiumspread.domain.accountpnl.AccountAlertSettings
import io.premiumspread.domain.accountpnl.ForeignPerpAccountReadPort
import io.premiumspread.domain.accountpnl.ForeignPerpAccountSnapshot
import io.premiumspread.domain.accountpnl.ForeignPerpMarginType
import io.premiumspread.domain.accountpnl.ForeignPerpPositionRisk
import io.premiumspread.domain.accountpnl.ForeignPerpPositionSource
import io.premiumspread.domain.accountpnl.ForeignPerpSymbolConfig
import io.premiumspread.domain.accountpnl.KoreaSpotAccountReadPort
import io.premiumspread.domain.accountpnl.KoreaSpotAccountSnapshot
import io.premiumspread.domain.accountpnl.PriceLevel
import io.premiumspread.domain.exchangerate.ExchangeRateSnapshot
import io.premiumspread.domain.job.JobId
import io.premiumspread.domain.job.JobLastSuccessReadPort
import io.premiumspread.domain.market.FxRateReadPort
import io.premiumspread.domain.owneralert.DiscordPush
import io.premiumspread.domain.owneralert.DiscordRoute
import io.premiumspread.domain.owneralert.DiscordTarget
import io.premiumspread.domain.owneralert.OwnerAlertChannel
import io.premiumspread.domain.owneralert.OwnerAlertChannels
import io.premiumspread.domain.owneralert.OwnerAlertRouting
import io.premiumspread.domain.ticker.Currency
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * 청산 근접 단계 알림의 Job 조합 (#141 dod AC7).
 *
 * 숏 진입가 70000·청산가 80000 이라 마크가 75000 이면 진행률 50%, 77000 이면 70%, 73500 이면 35% 다.
 */
class AccountPnlMonitorJobLiquidationTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private val clock = MutableClock(Instant.parse("2026-11-12T00:30:00Z"))
    private val executor = mockk<JobExecutor>()

    private lateinit var foreign: Foreign
    private lateinit var store: FakeOwnerAlertStore

    @BeforeEach
    fun setUp() {
        every { executor.execute(any(), any()) } answers { secondArg<() -> JobResult>().invoke() }
        foreign = Foreign(clock)
        store = FakeOwnerAlertStore()
    }

    private fun job() = AccountPnlMonitorJob(
        Korea(clock),
        foreign,
        Fx(clock),
        LastSuccess(clock),
        store,
        FakeMailSender(),
        executor,
        clock,
        zone,
        routing = BOTH,
        discordSender = FakeDiscordSender(),
        accountAlerts = AccountAlertSettings(),
    )

    private fun round(job: AccountPnlMonitorJob, mark: String, liquidation: String? = "80000") {
        foreign.risk = risk(mark, liquidation)
        clock.advance(Duration.ofMinutes(1))
        assertThat(job.run()).isEqualTo(JobResult.Success)
    }

    private fun rows(prefix: String) = store.rows.filter { it.mail.eventKey.startsWith(prefix) }

    @Test
    fun `숏 진행률이 경계를 넘으면 save 한 번에 메일과 PNL 소리 행이 상태와 함께 들어간다`() {
        val job = job()
        round(job, "73500") // 35%
        assertThat(rows("account-pnl-liquidation-")).isEmpty()

        val version = store.state.version
        round(job, "75000") // 50%

        assertThat(store.state.version).isEqualTo(version + 1)
        val rows = rows("account-pnl-liquidation-")
        assertThat(rows).hasSize(2)
        assertThat(rows.map { it.mail.channel })
            .containsExactlyInAnyOrder(OwnerAlertChannel.EMAIL, OwnerAlertChannel.DISCORD)
        val discord = rows.single { it.mail.channel == OwnerAlertChannel.DISCORD }
        assertThat(discord.mail.discordRoute).isEqualTo(DiscordRoute(DiscordTarget.PNL, DiscordPush.SOUND))
        assertThat(store.state.account.liquidation.level).isEqualTo(50)
    }

    @Test
    fun `수동 모드 회차는 청산 근접 행이 없다`() {
        foreign.source = ForeignPerpPositionSource.MANUAL_ENV
        val job = job()

        round(job, "77000")

        assertThat(rows("account-pnl-liquidation-")).isEmpty()
    }

    @Test
    fun `청산가가 없는 회차는 청산 근접 행이 없다`() {
        val job = job()

        round(job, "77000", liquidation = null)

        assertThat(rows("account-pnl-liquidation-")).isEmpty()
        assertThat(store.state.account.liquidation.level).isNull()
    }

    @Test
    fun `완화 알림은 Discord PNL 무음 행 하나이고 메일은 없다`() {
        val job = job()
        round(job, "77000") // 70%
        round(job, "73500") // 35% - 70 을 재무장

        val eased = rows("account-pnl-liquidation-eased-")
        assertThat(eased).hasSize(1)
        assertThat(eased.single().mail.channel).isEqualTo(OwnerAlertChannel.DISCORD)
        assertThat(eased.single().mail.discordRoute).isEqualTo(DiscordRoute(DiscordTarget.PNL, DiscordPush.SILENT))
    }

    private fun risk(mark: String, liquidation: String?) = ForeignPerpPositionRisk(
        liquidationPrice = liquidation?.let { BigDecimal(it) },
        markPrice = BigDecimal(mark),
        isolatedMargin = BigDecimal.ZERO,
        isolatedWallet = BigDecimal.ZERO,
        maintMargin = BigDecimal.ZERO,
        positionInitialMargin = BigDecimal.ZERO,
        notional = BigDecimal("700"),
    )

    // ---- fakes ----

    private class Korea(private val clock: Clock) : KoreaSpotAccountReadPort {
        override fun read(): KoreaSpotAccountSnapshot = KoreaSpotAccountSnapshot(
            quantity = BigDecimal("0.01"),
            averageBuyPrice = BigDecimal("100000000"),
            averageBuyPriceModified = false,
            entryFeeKrw = BigDecimal.ZERO,
            sellFeeRate = BigDecimal.ZERO,
            bidLevels = listOf(PriceLevel(BigDecimal("100000000"), BigDecimal("1"))),
            lastPrice = BigDecimal("100000000"),
            bidObservedAt = clock.instant(),
        )
    }

    private class Foreign(private val clock: Clock) : ForeignPerpAccountReadPort {
        var risk: ForeignPerpPositionRisk? = null
        var source = ForeignPerpPositionSource.EXCHANGE_API

        override fun read(): ForeignPerpAccountSnapshot = ForeignPerpAccountSnapshot(
            signedQuantity = BigDecimal("-0.01"),
            entryPrice = BigDecimal("70000"),
            entryCommissionUsdt = BigDecimal.ZERO,
            fundingUsdt = BigDecimal.ZERO,
            takerFeeRate = BigDecimal.ZERO,
            askLevels = listOf(PriceLevel(BigDecimal("70000"), BigDecimal("1"))),
            lastPrice = BigDecimal("70000"),
            askObservedAt = clock.instant(),
            positionSource = source,
            positionRisk = risk,
            symbolConfig = ForeignPerpSymbolConfig(5, ForeignPerpMarginType.ISOLATED),
        )
    }

    private class Fx(private val clock: Clock) : FxRateReadPort {
        override fun findLatest(base: Currency, quote: Currency): ExchangeRateSnapshot =
            ExchangeRateSnapshot("USD", "KRW", BigDecimal("1400"), clock.instant().minus(Duration.ofMinutes(1)))
    }

    private class LastSuccess(private val clock: Clock) : JobLastSuccessReadPort {
        override fun findLastSuccess(jobId: JobId): Instant = clock.instant().minus(Duration.ofMinutes(10))
    }

    private companion object {
        val BOTH = OwnerAlertRouting(OwnerAlertChannels(email = true, discord = true))
    }
}
