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
import io.premiumspread.application.notification.owneralert.OwnerAlertDispatcher
import io.premiumspread.domain.accountpnl.AccountHedgePosition
import io.premiumspread.domain.accountpnl.AccountNetPnl
import io.premiumspread.domain.accountpnl.AccountPnlAlertContent
import io.premiumspread.domain.accountpnl.AccountPnlMail
import io.premiumspread.domain.accountpnl.AccountPnlMailSender
import io.premiumspread.domain.accountpnl.AccountPnlMonitorState
import io.premiumspread.domain.accountpnl.AccountReadException
import io.premiumspread.domain.accountpnl.AccountReadFailureCategory
import io.premiumspread.domain.accountpnl.ForeignPerpAccountReadPort
import io.premiumspread.domain.accountpnl.ForeignPerpAccountSnapshot
import io.premiumspread.domain.accountpnl.KoreaSpotAccountReadPort
import io.premiumspread.domain.accountpnl.KoreaSpotAccountSnapshot
import io.premiumspread.domain.accountpnl.OutageState
import io.premiumspread.domain.accountpnl.OwnerAlertRows
import io.premiumspread.domain.accountpnl.PnlAlertState
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
import io.premiumspread.domain.owneralert.OwnerAlertDiscordSender
import io.premiumspread.domain.owneralert.OwnerAlertRoute
import io.premiumspread.domain.owneralert.OwnerAlertRouting
import io.premiumspread.domain.owneralert.OwnerAlertSendException
import io.premiumspread.domain.ticker.Currency
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * Job 의 owner 알림 라우팅과 채널별 전달 (dod AC8-b·c·d, design §3·§5).
 *
 * 시나리오 수치는 동결 `AccountPnlMonitorJobTest` 와 같다 — 순수익률 약 1.96% (빗썸 0.01 BTC 평단 1억 → 매수호가 1.02억,
 * 바이낸스 진입가 = 매도호가, 수수료 0). t0 = 2026-10-20 12:00 KST.
 */
class AccountPnlMonitorJobOwnerAlertTest {
    private val t0 = Instant.parse("2026-10-20T03:00:00Z")
    private val t0Millis = t0.toEpochMilli()
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
        hourly: Boolean = false,
        mailSender: AccountPnlMailSender = mail,
        discordSender: OwnerAlertDiscordSender? = discord,
    ) = AccountPnlMonitorJob(
        korea,
        foreign,
        Fx(clock),
        LastSuccess(clock),
        store,
        mailSender,
        executor,
        clock,
        zone,
        hourlyReportEnabled = hourly,
        routing = routing,
        discordSender = discordSender,
    )

    // ---- 라우팅 (AC8-b) ----

    @Test
    fun `메일과 Discord 가 모두 켜지면 수익 알림은 메일 다음 Discord 2행이고 둘 다 보낸다`() {
        assertThat(job().run()).isEqualTo(JobResult.Success)

        val key = "account-pnl-profit-$t0Millis"
        assertThat(store.rows.map { it.mail.channel }).containsExactly(OwnerAlertChannel.EMAIL, OwnerAlertChannel.DISCORD)
        assertThat(store.rows.map { it.mail.eventKey }).containsOnly(key)
        assertThat(store.rows.map { it.status }).containsOnly(Status.SENT)

        // Job 과 같은 입력(호가·환율 관측 t0−1m·계산 시각 t0)으로 렌더러 결과를 만든다
        val pnl = expectedPnl()
        val email = store.rows[0].mail
        assertThat(email.subject).startsWith("[실계정] 수익 알림 +1.96%")
        assertThat(email).isEqualTo(OwnerAlertRows.expand(AccountPnlAlertContent.profit(pnl, zone), OwnerAlertRoute(email = true, discord = null)).single())
        assertThat(mail.sent).containsExactly(email)

        val discordRow = store.rows[1].mail
        val message = AccountPnlAlertContent.profit(pnl, zone).discord.toDiscordMessage()
        assertThat(message.title).startsWith("💰 수익 알림 +1.96%")
        assertThat(discordRow).isEqualTo(
            AccountPnlMail(
                key,
                message.title,
                message.description,
                OwnerAlertChannel.DISCORD,
                DiscordRoute(DiscordTarget.PNL, DiscordPush.HERE),
                AccountPnlAlertContent.COLOR_PROFIT,
                message.layout,
            ),
        )
        assertThat(discord.sent).containsExactly(DiscordRoute(DiscordTarget.PNL, DiscordPush.HERE) to message)
    }

    @Test
    fun `정시 보고는 Discord PNL SILENT 1행만 만든다`() {
        korea.bid = BigDecimal("100500000") // 0.50% — 수익 알림 없음

        job(hourly = true).run()

        val row = store.rows.single()
        assertThat(row.mail.eventKey).isEqualTo("account-pnl-hourly-$t0Millis")
        assertThat(row.mail.channel).isEqualTo(OwnerAlertChannel.DISCORD)
        assertThat(row.mail.discordRoute).isEqualTo(DiscordRoute(DiscordTarget.PNL, DiscordPush.SILENT))
        assertThat(row.mail.subject).isEqualTo("📊 정시 보고 12:00 +0.50% +5,000원")
        assertThat(row.status).isEqualTo(Status.SENT)
    }

    @Test
    fun `중단 알림은 메일과 Discord OPS SOUND 2행이다`() {
        givenOutageOverFiveMinutes()

        job().run()

        assertThat(store.rows.map { it.mail.eventKey }).containsOnly("account-pnl-outage-$t0Millis")
        assertThat(store.rows.map { it.mail.channel }).containsExactly(OwnerAlertChannel.EMAIL, OwnerAlertChannel.DISCORD)
        assertThat(store.rows[0].mail.subject).startsWith("[실계정] 모니터링 중단 — 빗썸 인증 실패")
        assertThat(store.rows[1].mail.discordRoute).isEqualTo(DiscordRoute(DiscordTarget.OPS, DiscordPush.SOUND))
        assertThat(store.rows[1].mail.subject).startsWith("🚨 모니터링 중단 — 빗썸 인증 실패")
    }

    @Test
    fun `복구 알림은 Discord OPS SOUND 1행만 만든다`() {
        givenOutageInProgress()

        job().run()

        val row = store.rows.single()
        assertThat(row.mail.eventKey).isEqualTo("account-pnl-recovered-$t0Millis")
        assertThat(row.mail.channel).isEqualTo(OwnerAlertChannel.DISCORD)
        assertThat(row.mail.discordRoute).isEqualTo(DiscordRoute(DiscordTarget.OPS, DiscordPush.SOUND))
        assertThat(row.mail.subject).isEqualTo("✅ 모니터링 복구")
    }

    @Test
    fun `메일만이면 4종 모두 기존과 같은 eventKey 의 메일 1행씩이다`() {
        job(routing = OwnerAlertRouting.EMAIL_ONLY, hourly = true, discordSender = null).run()
        assertEmailOnly("account-pnl-profit-$t0Millis", "account-pnl-hourly-$t0Millis")

        setUp()
        givenOutageOverFiveMinutes()
        job(routing = OwnerAlertRouting.EMAIL_ONLY, discordSender = null).run()
        assertEmailOnly("account-pnl-outage-$t0Millis")

        setUp()
        givenOutageInProgress()
        job(routing = OwnerAlertRouting.EMAIL_ONLY, discordSender = null).run()
        assertEmailOnly("account-pnl-recovered-$t0Millis")
    }

    @Test
    fun `Discord 만 켜지면 메일 행을 만들지 않는다`() {
        val discordOnly = OwnerAlertRouting(OwnerAlertChannels(email = false, discord = true))

        job(routing = discordOnly, hourly = true, mailSender = OwnerAlertDispatcher.UNAVAILABLE_MAIL_SENDER).run()
        assertDiscordOnly("account-pnl-profit-$t0Millis", "account-pnl-hourly-$t0Millis")

        setUp()
        givenOutageOverFiveMinutes()
        job(routing = discordOnly, mailSender = OwnerAlertDispatcher.UNAVAILABLE_MAIL_SENDER).run()
        assertDiscordOnly("account-pnl-outage-$t0Millis")
    }

    // ---- 채널별 전달 (AC8-c) ----

    @Test
    fun `Discord 재시도 가능 실패는 메일을 막지 않고 다음 회차의 새 Job 이 다시 보낸다`() {
        discord.failure = { OwnerAlertSendException(false, "Discord webhook PNL returned 503") }

        assertThat(job().run()).isEqualTo(JobResult.Success)

        assertThat(store.rows.map { it.mail.channel to it.status })
            .containsExactly(OwnerAlertChannel.EMAIL to Status.SENT, OwnerAlertChannel.DISCORD to Status.PENDING)
        assertThat(store.rows[1].attempts).isEqualTo(1)

        discord.failure = { null }
        clock.advance(Duration.ofMinutes(1))
        assertThat(job().run()).isEqualTo(JobResult.Success)

        assertThat(store.rows).hasSize(2)
        assertThat(store.rows[1].status).isEqualTo(Status.SENT)
        assertThat(discord.attempts).hasSize(2)
        assertThat(mail.attempts).hasSize(1)
    }

    @Test
    fun `Discord 재시도 불가 실패는 즉시 FAILED 이고 다음 회차에 다시 보내지 않는다`() {
        discord.failure = { OwnerAlertSendException(true, "Discord webhook rejected: PNL") }

        assertThat(job().run()).isEqualTo(JobResult.Success)

        assertThat(store.rows[1].status).isEqualTo(Status.FAILED)
        assertThat(store.rows[1].attempts).isEqualTo(1)

        discord.failure = { null }
        clock.advance(Duration.ofMinutes(1))
        job().run()

        assertThat(discord.attempts).hasSize(1)
        assertThat(store.rows[1].status).isEqualTo(Status.FAILED)
    }

    // ---- 생성 검증 (AC8-d) ----

    @Test
    fun `Discord 채널을 켜고 Discord sender 를 주지 않으면 생성할 수 없다`() {
        assertThatThrownBy { job(discordSender = null) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("owner-alert discord channel is enabled but no Discord sender was provided")
    }

    // ---- helpers ----

    private fun expectedPnl(): AccountNetPnl {
        val position = checkNotNull(AccountHedgePosition.resolve(korea.read(), foreign.read()))
        return AccountNetPnl.calculate(position, BigDecimal("1400"), t0.minus(Duration.ofMinutes(1)), t0)
    }

    private fun assertEmailOnly(vararg keys: String) {
        assertThat(store.rows.map { it.mail.eventKey }).containsExactly(*keys)
        assertThat(store.rows.map { it.mail.channel }).containsOnly(OwnerAlertChannel.EMAIL)
        assertThat(store.rows.map { it.mail })
            .allSatisfy { assertThat(it).isEqualTo(AccountPnlMail(it.eventKey, it.subject, it.body)) }
        assertThat(mail.sent).hasSize(keys.size)
    }

    private fun assertDiscordOnly(vararg keys: String) {
        assertThat(store.rows.map { it.mail.eventKey }).containsExactly(*keys)
        assertThat(store.rows.map { it.mail.channel }).containsOnly(OwnerAlertChannel.DISCORD)
        assertThat(store.rows.map { it.status }).containsOnly(Status.SENT)
    }

    /** 마지막 계산 성공이 6분 전이고 이번 회차가 빗썸 인증 실패. */
    private fun givenOutageOverFiveMinutes() {
        store.state = AccountPnlMonitorState(
            PnlAlertState.INITIAL,
            OutageState(t0.minus(Duration.ofMinutes(6)), t0.minus(Duration.ofHours(1)), false),
            7,
        )
        korea.failure = AccountReadException(AccountReadFailureCategory.BITHUMB_AUTH, "bithumb auth failed")
    }

    /** 중단 알림을 보낸 상태에서 포지션 없음(성공 회차) — 복구. */
    private fun givenOutageInProgress() {
        store.state = AccountPnlMonitorState(
            PnlAlertState.INITIAL,
            OutageState(t0.minus(Duration.ofMinutes(10)), t0.minus(Duration.ofHours(1)), true),
            3,
        )
        foreign.signedQuantity = BigDecimal.ZERO
    }

    // ---- fakes (동결 테스트와 같은 시나리오 수치) ----

    private class Korea(private val clock: Clock) : KoreaSpotAccountReadPort {
        var bid = BigDecimal("102000000")
        var failure: Exception? = null

        override fun read(): KoreaSpotAccountSnapshot {
            failure?.let { throw it }
            return KoreaSpotAccountSnapshot(
                quantity = BigDecimal("0.01"),
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

    private companion object {
        val BOTH = OwnerAlertRouting(OwnerAlertChannels(email = true, discord = true))
    }
}
