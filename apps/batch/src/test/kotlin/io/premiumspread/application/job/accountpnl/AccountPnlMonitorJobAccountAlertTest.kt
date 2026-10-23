package io.premiumspread.application.job.accountpnl

import io.mockk.every
import io.mockk.mockk
import io.premiumspread.application.common.JobExecutor
import io.premiumspread.application.common.JobResult
import io.premiumspread.application.notification.owneralert.FakeDiscordSender
import io.premiumspread.application.notification.owneralert.FakeMailSender
import io.premiumspread.application.notification.owneralert.FakeOwnerAlertStore
import io.premiumspread.application.notification.owneralert.FakeOwnerAlertStore.Status
import io.premiumspread.application.notification.owneralert.MutableClock
import io.premiumspread.domain.accountpnl.AccountAlertContent
import io.premiumspread.domain.accountpnl.AccountAlertSettings
import io.premiumspread.domain.accountpnl.AccountAlertState
import io.premiumspread.domain.accountpnl.AccountPnlAlertStore
import io.premiumspread.domain.accountpnl.AccountReadException
import io.premiumspread.domain.accountpnl.AccountReadFailureCategory
import io.premiumspread.domain.accountpnl.ForeignLeg
import io.premiumspread.domain.accountpnl.ForeignPerpAccountReadPort
import io.premiumspread.domain.accountpnl.ForeignPerpAccountSnapshot
import io.premiumspread.domain.accountpnl.ForeignPerpPositionSource
import io.premiumspread.domain.accountpnl.KoreaLeg
import io.premiumspread.domain.accountpnl.KoreaSpotAccountReadPort
import io.premiumspread.domain.accountpnl.KoreaSpotAccountSnapshot
import io.premiumspread.domain.accountpnl.PositionLegs
import io.premiumspread.domain.accountpnl.PositionWatchState
import io.premiumspread.domain.accountpnl.PriceLevel
import io.premiumspread.domain.accountpnl.ReminderState
import io.premiumspread.domain.accountpnl.SwingState
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
 * Job 의 owner 계정 알림 판정·라우팅·상태 저장 (dod AC10, design §5).
 * 시작 시각 2026-10-20 09:30 KST. 기본 fixture 는 미반영 없음·manual 출처이고 순수익률은 약 0% 다(수익 알림 없음).
 */
class AccountPnlMonitorJobAccountAlertTest {
    private val t0 = Instant.parse("2026-10-20T00:30:00Z")
    private val zone = ZoneId.of("Asia/Seoul")
    private val clock = MutableClock(t0)
    private val executor = mockk<JobExecutor>()

    private lateinit var korea: Korea
    private lateinit var foreign: Foreign
    private lateinit var store: FakeOwnerAlertStore
    private lateinit var mail: FakeMailSender
    private lateinit var discord: FakeDiscordSender

    @BeforeEach
    fun setUp() {
        every { executor.execute(any(), any()) } answers { secondArg<() -> JobResult>().invoke() }
        korea = Korea(clock)
        foreign = Foreign(clock)
        store = FakeOwnerAlertStore()
        mail = FakeMailSender()
        discord = FakeDiscordSender()
    }

    private fun job(
        routing: OwnerAlertRouting = BOTH,
        settings: AccountAlertSettings? = AccountAlertSettings(),
        alertStore: AccountPnlAlertStore = store,
    ) = AccountPnlMonitorJob(
        korea,
        foreign,
        Fx(clock),
        LastSuccess(clock),
        alertStore,
        mail,
        executor,
        clock,
        zone,
        hourlyReportEnabled = false,
        routing = routing,
        discordSender = if (routing.channels.discord) discord else null,
        accountAlerts = settings,
    )

    private fun rows(prefix: String) = store.rows.filter { it.mail.eventKey.startsWith(prefix) }

    @Test
    fun `설정이 없으면 계정 알림을 만들지 않고 상태를 그대로 둔다`() {
        val arbitrary = AccountAlertState(
            PositionWatchState(PositionLegs(KoreaLeg(BigDecimal("0.5"), BigDecimal("100000000")), ForeignLeg(BigDecimal("-0.5"), BigDecimal("70000"))), true),
            SwingState(Instant.parse("2026-10-19T23:00:00Z"), BigDecimal("0.10"), true, false),
            ReminderState(Instant.parse("2026-10-18T15:00:00Z"), BigDecimal("3.2"), Instant.parse("2026-10-18T00:00:00Z")),
        )
        store.state = store.state.copy(account = arbitrary)
        val job = job(settings = null)

        job.run()
        korea.quantity = BigDecimal("0.02")
        clock.advance(Duration.ofMinutes(1))
        job.run()
        korea.bid = BigDecimal("101000000")
        clock.advance(Duration.ofMinutes(1))
        job.run()
        korea.entryFeeKrw = null
        clock.advance(Duration.ofHours(5))
        job.run()

        assertThat(rows("account-pnl-position-")).isEmpty()
        assertThat(rows("account-pnl-swing-")).isEmpty()
        assertThat(rows("account-pnl-reminder-")).isEmpty()
        assertThat(store.state.account).isEqualTo(arbitrary)
    }

    @Test
    fun `첫 회차는 leg 만 기록한다`() {
        assertThat(job().run()).isEqualTo(JobResult.Success)

        assertThat(store.rows).isEmpty()
        assertThat(store.state.account.position.legs).isNotNull()
        assertThat(store.state.account.reminder.lastDayStart).isNull()
    }

    @Test
    fun `빗썸만 바뀐 수동 회차는 메일과 Discord @here 로 변경을 알리고 경고는 한 번뿐이다`() {
        job().run()
        korea.quantity = BigDecimal("0.02")
        clock.advance(Duration.ofMinutes(1))
        job().run()

        val first = rows("account-pnl-position-")
        assertThat(first.map { it.mail.channel }).containsExactly(OwnerAlertChannel.EMAIL, OwnerAlertChannel.DISCORD)
        assertThat(first.map { it.status }).containsOnly(Status.SENT)
        assertThat(first[1].mail.discordRoute).isEqualTo(DiscordRoute(DiscordTarget.PNL, DiscordPush.HERE))
        assertThat(first[1].mail.discordColor).isEqualTo(AccountAlertContent.COLOR_HEDGE_MISMATCH)

        korea.quantity = BigDecimal("0.03")
        clock.advance(Duration.ofMinutes(1))
        job().run()

        val all = rows("account-pnl-position-")
        assertThat(all).hasSize(4)
        assertThat(all.last().mail.discordColor).isEqualTo(AccountAlertContent.COLOR_POSITION_CHANGED)
    }

    @Test
    fun `같은 시간 안에 0_5퍼센트p 움직이면 Discord 소리로 한 번만 알린다`() {
        val job = job()
        job.run()
        korea.bid = BigDecimal("100600000")
        clock.advance(Duration.ofMinutes(1))
        job.run()

        val swing = rows("account-pnl-swing-")
        assertThat(swing).hasSize(1)
        assertThat(swing.single().mail.channel).isEqualTo(OwnerAlertChannel.DISCORD)
        assertThat(swing.single().mail.discordRoute).isEqualTo(DiscordRoute(DiscordTarget.PNL, DiscordPush.SOUND))

        korea.bid = BigDecimal("100900000")
        clock.advance(Duration.ofMinutes(1))
        job.run()
        assertThat(rows("account-pnl-swing-")).hasSize(1)
    }

    @Test
    fun `미반영이 처음 생긴 회차에 무음 리마인더 한 번`() {
        val job = job()
        job.run()
        assertThat(store.rows).isEmpty()

        clock.advance(Duration.ofHours(5))
        korea.entryFeeKrw = null
        job.run()
        val reminder = rows("account-pnl-reminder-")
        assertThat(reminder).hasSize(1)
        assertThat(reminder.single().mail.channel).isEqualTo(OwnerAlertChannel.DISCORD)
        assertThat(reminder.single().mail.discordRoute).isEqualTo(DiscordRoute(DiscordTarget.PNL, DiscordPush.SILENT))

        clock.advance(Duration.ofHours(1))
        foreign.fundingUsdt = null
        job.run()
        assertThat(rows("account-pnl-reminder-")).hasSize(1)
    }

    @Test
    fun `관측 실패 회차는 계정 알림 상태를 바꾸지 않는다`() {
        val job = job()
        job.run()
        val before = store.state.account
        val rowCount = store.rows.size

        korea.failure = AccountReadException(AccountReadFailureCategory.NETWORK, "down")
        clock.advance(Duration.ofMinutes(1))
        job.run()

        assertThat(store.state.account).isEqualTo(before)
        assertThat(store.rows.size).isEqualTo(rowCount)
    }

    @Test
    fun `메일만 쓰면 세 종류 모두 메일 한 통씩`() {
        val job = job(routing = OwnerAlertRouting.EMAIL_ONLY)
        job.run()
        korea.quantity = BigDecimal("0.02")
        clock.advance(Duration.ofMinutes(1))
        job.run()
        // 포지션 변경 회차는 급변 기준선을 비우므로 다음 정시 버킷에서 기준선을 다시 잡는다
        clock.advance(Duration.ofMinutes(31))
        job.run()
        korea.bid = BigDecimal("100600000")
        clock.advance(Duration.ofMinutes(1))
        job.run()
        korea.entryFeeKrw = null
        clock.advance(Duration.ofHours(5))
        job.run()

        for (prefix in listOf("account-pnl-position-", "account-pnl-swing-", "account-pnl-reminder-")) {
            val found = rows(prefix)
            assertThat(found).hasSize(1)
            assertThat(found.single().mail.channel).isEqualTo(OwnerAlertChannel.EMAIL)
        }
        assertThat(discord.attempts).isEmpty()
    }

    @Test
    fun `version 충돌이면 저장하지 않고 다음 회차가 다시 판정한다`() {
        val conflicting = ConflictingStore(store)
        job(alertStore = conflicting).run()
        val legsBefore = store.state.account.position.legs

        korea.quantity = BigDecimal("0.02")
        clock.advance(Duration.ofMinutes(1))
        conflicting.conflictNext = true
        val result = job(alertStore = conflicting).run()

        assertThat(result).isEqualTo(JobResult.Skipped("state-version-conflict"))
        assertThat(store.rows).isEmpty()
        assertThat(store.state.account.position.legs).isEqualTo(legsBefore)

        clock.advance(Duration.ofMinutes(1))
        job(alertStore = conflicting).run()
        val retried = rows("account-pnl-position-")
        assertThat(retried.map { it.mail.channel }).containsExactly(OwnerAlertChannel.EMAIL, OwnerAlertChannel.DISCORD)
        assertThat(retried[1].mail.discordColor).isEqualTo(AccountAlertContent.COLOR_HEDGE_MISMATCH)
    }

    // ---- fakes ----

    private class ConflictingStore(private val inner: FakeOwnerAlertStore) : AccountPnlAlertStore by inner {
        var conflictNext = false

        override fun save(
            expectedVersion: Long,
            state: io.premiumspread.domain.accountpnl.AccountPnlMonitorState,
            mails: List<io.premiumspread.domain.accountpnl.AccountPnlMail>,
        ): Boolean {
            if (conflictNext) {
                conflictNext = false
                inner.state = inner.state.copy(version = inner.state.version + 1)
            }
            return inner.save(expectedVersion, state, mails)
        }
    }

    private class Korea(private val clock: Clock) : KoreaSpotAccountReadPort {
        var bid = BigDecimal("100000000")
        var quantity = BigDecimal("0.01")
        var entryFeeKrw: BigDecimal? = BigDecimal.ZERO
        var failure: Exception? = null

        override fun read(): KoreaSpotAccountSnapshot {
            failure?.let { throw it }
            return KoreaSpotAccountSnapshot(
                quantity = quantity,
                averageBuyPrice = BigDecimal("100000000"),
                averageBuyPriceModified = false,
                entryFeeKrw = entryFeeKrw,
                sellFeeRate = BigDecimal.ZERO,
                bidLevels = listOf(PriceLevel(bid, BigDecimal("1"))),
                lastPrice = bid,
                bidObservedAt = clock.instant(),
            )
        }
    }

    private class Foreign(private val clock: Clock) : ForeignPerpAccountReadPort {
        var fundingUsdt: BigDecimal? = BigDecimal.ZERO

        override fun read(): ForeignPerpAccountSnapshot = ForeignPerpAccountSnapshot(
            signedQuantity = BigDecimal("-0.01"),
            entryPrice = BigDecimal("70000"),
            entryCommissionUsdt = BigDecimal.ZERO,
            fundingUsdt = fundingUsdt,
            takerFeeRate = BigDecimal.ZERO,
            askLevels = listOf(PriceLevel(BigDecimal("70000"), BigDecimal("1"))),
            lastPrice = BigDecimal("70000"),
            askObservedAt = clock.instant(),
            positionSource = ForeignPerpPositionSource.MANUAL_ENV,
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
