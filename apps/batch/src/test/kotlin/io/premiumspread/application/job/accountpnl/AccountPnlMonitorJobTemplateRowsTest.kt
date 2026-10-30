package io.premiumspread.application.job.accountpnl

import io.mockk.every
import io.mockk.mockk
import io.premiumspread.application.common.JobExecutor
import io.premiumspread.application.common.JobResult
import io.premiumspread.application.notification.owneralert.FakeDiscordSender
import io.premiumspread.application.notification.owneralert.FakeMailSender
import io.premiumspread.application.notification.owneralert.FakeOwnerAlertStore
import io.premiumspread.application.notification.owneralert.MutableClock
import io.premiumspread.domain.accountpnl.AccountAlert
import io.premiumspread.domain.accountpnl.AccountAlertContent
import io.premiumspread.domain.accountpnl.AccountAlertSettings
import io.premiumspread.domain.accountpnl.AccountHedgePosition
import io.premiumspread.domain.accountpnl.AccountNetPnl
import io.premiumspread.domain.accountpnl.AccountPnlAlertContent
import io.premiumspread.domain.accountpnl.AccountPnlMonitorState
import io.premiumspread.domain.accountpnl.AccountReadException
import io.premiumspread.domain.accountpnl.AccountReadFailureCategory
import io.premiumspread.domain.accountpnl.ForeignLeg
import io.premiumspread.domain.accountpnl.ForeignPerpAccountReadPort
import io.premiumspread.domain.accountpnl.ForeignPerpPositionSource
import io.premiumspread.domain.accountpnl.KoreaLeg
import io.premiumspread.domain.accountpnl.PositionLegs
import io.premiumspread.domain.accountpnl.ForeignPerpAccountSnapshot
import io.premiumspread.domain.accountpnl.KoreaSpotAccountReadPort
import io.premiumspread.domain.accountpnl.KoreaSpotAccountSnapshot
import io.premiumspread.domain.accountpnl.OutageNotice
import io.premiumspread.domain.accountpnl.OutageState
import io.premiumspread.domain.accountpnl.PnlAlertState
import io.premiumspread.domain.accountpnl.PriceLevel
import io.premiumspread.domain.exchangerate.ExchangeRateSnapshot
import io.premiumspread.domain.job.JobId
import io.premiumspread.domain.job.JobLastSuccessReadPort
import io.premiumspread.domain.market.FxRateReadPort
import io.premiumspread.domain.owneralert.OwnerAlertChannel
import io.premiumspread.domain.owneralert.OwnerAlertChannels
import io.premiumspread.domain.owneralert.OwnerAlertKind
import io.premiumspread.domain.owneralert.OwnerAlertRouteOverride
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
 * Job 이 만드는 행이 템플릿 결과와 같다 (dod AC15(b)). 모든 채널을 켠 라우팅 + 해당 종류 메일 override 로
 * EMAIL·DISCORD 2행이 생기고, DISCORD 행은 layout 을 갖고, EMAIL 본문은 같은 입력의 템플릿 `mail.body()` 와 같다.
 * fake 구성은 같은 디렉터리 `AccountPnlMonitorJobOwnerAlertTest` 의 복제다(R3 — 기존 파일 비의존).
 */
class AccountPnlMonitorJobTemplateRowsTest {
    private val t0 = Instant.parse("2026-10-20T03:00:00Z")
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

    private fun job(kind: OwnerAlertKind, accountAlerts: AccountAlertSettings? = null) = AccountPnlMonitorJob(
        korea,
        foreign,
        Fx(clock),
        LastSuccess(clock),
        store,
        mail,
        executor,
        clock,
        zone,
        routing = OwnerAlertRouting(
            OwnerAlertChannels(email = true, discord = true),
            mapOf(kind to OwnerAlertRouteOverride(email = true)),
        ),
        discordSender = discord,
        accountAlerts = accountAlerts,
    )

    @Test
    fun `수익 회차는 EMAIL DISCORD 2행이고 본문과 layout 이 템플릿 결과와 같다`() {
        assertThat(job(OwnerAlertKind.PROFIT).run()).isEqualTo(JobResult.Success)

        val message = AccountPnlAlertContent.profit(expectedPnl(), zone)
        val rows = store.rows.map { it.mail }
        assertThat(rows.map { it.channel }).containsExactly(OwnerAlertChannel.EMAIL, OwnerAlertChannel.DISCORD)
        assertThat(rows[0].body).isEqualTo(message.mail.body())
        assertThat(rows[1].discordLayout).isNotNull
        assertThat(rows[1].discordLayout).isEqualTo(message.discord.toDiscordMessage().layout)
    }

    @Test
    fun `중단 회차는 EMAIL DISCORD 2행이고 본문과 layout 이 템플릿 결과와 같다`() {
        store.state = AccountPnlMonitorState(
            PnlAlertState.INITIAL,
            OutageState(t0.minus(Duration.ofMinutes(6)), t0.minus(Duration.ofHours(1)), false),
            7,
        )
        korea.failure = AccountReadException(AccountReadFailureCategory.BITHUMB_AUTH, "bithumb auth failed")

        assertThat(job(OwnerAlertKind.MONITORING_OUTAGE).run()).isEqualTo(JobResult.Success)

        val message = AccountPnlAlertContent.outage(
            OutageNotice.Outage(AccountReadFailureCategory.BITHUMB_AUTH, t0.minus(Duration.ofMinutes(6))),
            t0,
            zone,
        )
        val rows = store.rows.map { it.mail }
        assertThat(rows.map { it.channel }).containsExactly(OwnerAlertChannel.EMAIL, OwnerAlertChannel.DISCORD)
        assertThat(rows[0].body).isEqualTo(message.mail.body())
        assertThat(rows[1].discordLayout).isNotNull
        assertThat(rows[1].discordLayout).isEqualTo(message.discord.toDiscordMessage().layout)
    }

    @Test
    fun `포지션 변경 회차는 EMAIL DISCORD 2행이고 본문과 layout 이 템플릿 결과와 같다`() {
        val job = job(OwnerAlertKind.POSITION_CHANGED, AccountAlertSettings())
        job.run()
        korea.quantity = BigDecimal("0.02")
        clock.advance(Duration.ofMinutes(1))
        job.run()

        val message = AccountAlertContent.message(
            AccountAlert.PositionChanged(
                before = PositionLegs(
                    KoreaLeg.of(BigDecimal("0.01"), BigDecimal("100000000")),
                    ForeignLeg.of(BigDecimal("-0.01"), BigDecimal("70000")),
                ),
                after = PositionLegs(
                    KoreaLeg.of(BigDecimal("0.02"), BigDecimal("100000000")),
                    ForeignLeg.of(BigDecimal("-0.01"), BigDecimal("70000")),
                ),
                hedgeMismatchWarning = false,
                source = ForeignPerpPositionSource.EXCHANGE_API,
                detectedAt = clock.instant(),
            ),
            zone,
        )
        val rows = store.rows.map { it.mail }.filter { it.eventKey.startsWith("account-pnl-position-") }
        assertThat(rows.map { it.channel }).containsExactly(OwnerAlertChannel.EMAIL, OwnerAlertChannel.DISCORD)
        assertThat(rows[0].body).isEqualTo(message.mail.body())
        assertThat(rows[1].discordLayout).isNotNull
        assertThat(rows[1].discordLayout).isEqualTo(message.discord.toDiscordMessage().layout)
    }

    private fun expectedPnl(): AccountNetPnl {
        val position = checkNotNull(AccountHedgePosition.resolve(korea.read(), foreign.read()))
        return AccountNetPnl.calculate(position, BigDecimal("1400"), t0.minus(Duration.ofMinutes(1)), t0)
    }

    // ---- fakes (동결 테스트와 같은 시나리오 수치) ----

    private class Korea(private val clock: Clock) : KoreaSpotAccountReadPort {
        var bid = BigDecimal("102000000")
        var quantity = BigDecimal("0.01")
        var failure: Exception? = null

        override fun read(): KoreaSpotAccountSnapshot {
            failure?.let { throw it }
            return KoreaSpotAccountSnapshot(
                quantity = quantity,
                averageBuyPrice = BigDecimal("100000000"),
                averageBuyPriceModified = false,
                entryFeeKrw = BigDecimal.ZERO,
                sellFeeRate = BigDecimal.ZERO,
                bidLevels = listOf(PriceLevel(bid, BigDecimal("1"))),
                lastPrice = bid,
                bidObservedAt = clock.instant(),
            )
        }
    }

    private class Foreign(private val clock: Clock) : ForeignPerpAccountReadPort {
        var signedQuantity = BigDecimal("-0.01")

        override fun read(): ForeignPerpAccountSnapshot = ForeignPerpAccountSnapshot(
            signedQuantity = signedQuantity,
            entryPrice = BigDecimal("70000"),
            entryCommissionUsdt = BigDecimal.ZERO,
            fundingUsdt = BigDecimal.ZERO,
            takerFeeRate = BigDecimal.ZERO,
            askLevels = listOf(PriceLevel(BigDecimal("70000"), BigDecimal("1"))),
            lastPrice = BigDecimal("70000"),
            askObservedAt = clock.instant(),
        )
    }

    private class Fx(private val clock: Clock) : FxRateReadPort {
        override fun findLatest(base: Currency, quote: Currency): ExchangeRateSnapshot =
            ExchangeRateSnapshot("USD", "KRW", BigDecimal("1400"), clock.instant().minus(Duration.ofMinutes(1)))
    }

    private class LastSuccess(private val clock: Clock) : JobLastSuccessReadPort {
        override fun findLastSuccess(jobId: JobId): Instant = clock.instant().minus(Duration.ofMinutes(10))
    }

}
