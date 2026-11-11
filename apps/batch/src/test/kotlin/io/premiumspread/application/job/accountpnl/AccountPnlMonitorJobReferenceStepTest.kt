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
import io.premiumspread.domain.accountpnl.AccountPositionHistoryStore
import io.premiumspread.domain.accountpnl.AccountReferencePositionReadPort
import io.premiumspread.domain.accountpnl.ForeignPerpAccountReadPort
import io.premiumspread.domain.accountpnl.ForeignPerpAccountSnapshot
import io.premiumspread.domain.accountpnl.ForeignPerpPositionSource
import io.premiumspread.domain.accountpnl.KoreaSpotAccountReadPort
import io.premiumspread.domain.accountpnl.KoreaSpotAccountSnapshot
import io.premiumspread.domain.accountpnl.PositionHistoryRound
import io.premiumspread.domain.accountpnl.PriceLevel
import io.premiumspread.domain.accountpnl.ReferenceLegs
import io.premiumspread.domain.accountpnl.ReferencePhase
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
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CancellationException

/**
 * 기준 포지션 단계 알림의 Job 조합 (#131 dod AC3·R1·R2).
 *
 * 기준 진입 김프는 빗썸 평단 1억·바이낸스 진입 7만·환율 1400 이라 2.00% 다 (김프 = (한국 − 해외×환율) / 한국).
 * 청산 호가 bid = 9800만 / (1 − 현재김프/100) 로 맞춰 차이(현재 − 진입)를 정확한 값으로 만든다.
 */
@ExtendWith(OutputCaptureExtension::class)
class AccountPnlMonitorJobReferenceStepTest {
    private val t0 = Instant.parse("2026-10-20T00:30:00Z")
    private val zone = ZoneId.of("Asia/Seoul")
    private val clock = MutableClock(t0)
    private val executor = mockk<JobExecutor>()

    private lateinit var korea: Korea
    private lateinit var foreign: Foreign
    private lateinit var store: FakeOwnerAlertStore
    private lateinit var discord: FakeDiscordSender

    @BeforeEach
    fun setUp() {
        every { executor.execute(any(), any()) } answers { secondArg<() -> JobResult>().invoke() }
        korea = Korea(clock)
        foreign = Foreign(clock)
        store = FakeOwnerAlertStore()
        discord = FakeDiscordSender()
    }

    @AfterEach
    fun clearInterrupt() {
        Thread.interrupted()
    }

    private fun job(
        referencePositions: AccountReferencePositionReadPort? = null,
        history: AccountPositionHistoryStore? = null,
    ) = AccountPnlMonitorJob(
        korea,
        foreign,
        Fx(clock),
        LastSuccess(clock),
        store,
        FakeMailSender(),
        executor,
        clock,
        zone,
        routing = BOTH,
        discordSender = discord,
        accountAlerts = AccountAlertSettings(),
        positionHistory = history,
        referencePositions = referencePositions,
    )

    private fun rows(prefix: String) = store.rows.filter { it.mail.eventKey.startsWith(prefix) }

    private fun round(job: AccountPnlMonitorJob, bid: String, holding: Boolean = true) {
        korea.bid = BigDecimal(bid)
        korea.quantity = if (holding) BigDecimal("0.01") else BigDecimal.ZERO
        foreign.quantity = if (holding) BigDecimal("-0.01") else BigDecimal.ZERO
        clock.advance(Duration.ofMinutes(1))
        assertThat(job.run()).isEqualTo(JobResult.Success)
    }

    /** eventKey `...-{up|down}-{level}-{epochMs}` 에서 level 만 뽑는다. */
    private fun levels(prefix: String) = rows(prefix).map { it.mail.eventKey.removePrefix(prefix).substringBeforeLast("-") }

    /** 보유 차이 0.1 → 0.5 → 0.7 → 1.0 후 전량 정리 0.0 → -0.5 → -1.0 시나리오. 상태 level 로 fixture 드리프트를 잡는다. */
    private fun runScenario(job: AccountPnlMonitorJob) {
        round(job, "100102145") // 차이 0.10
        assertThat(store.state.account.step.phase).isEqualTo(ReferencePhase.HOLDING)
        assertThat(store.state.account.step.level).isZero()
        round(job, "100512821") // 0.50
        assertThat(store.state.account.step.level).isEqualTo(1)
        round(job, "100719424") // 0.70
        assertThat(store.state.account.step.level).isEqualTo(1)
        round(job, "101030928") // 1.00
        assertThat(store.state.account.step.level).isEqualTo(2)

        round(job, "100000000", holding = false) // 0.00 (현재 2.00 - 진입 2.00)
        assertThat(store.state.account.step.phase).isEqualTo(ReferencePhase.FLAT)
        assertThat(store.state.account.step.level).isZero()
        round(job, "99492386", holding = false) // -0.50
        assertThat(store.state.account.step.level).isEqualTo(-1)
        round(job, "98989899", holding = false) // -1.00
        assertThat(store.state.account.step.level).isEqualTo(-2)
    }

    private fun assertScenarioRows() {
        val up = rows("account-pnl-reference-step-up-")
        val down = rows("account-pnl-reference-step-down-")
        assertThat(levels("account-pnl-reference-step-up-")).containsExactly("1", "2")
        assertThat(levels("account-pnl-reference-step-down-")).containsExactly("0", "-1", "-2")
        (up + down).forEach {
            assertThat(it.mail.channel).isEqualTo(OwnerAlertChannel.DISCORD)
            assertThat(it.mail.discordRoute).isEqualTo(DiscordRoute(DiscordTarget.PNL, DiscordPush.SOUND))
        }
        assertThat(rows("account-pnl-reference-step-").none { it.mail.channel == OwnerAlertChannel.EMAIL }).isTrue()
        assertThat(down.first().mail.subject + down.first().mail.body).contains("직전 진입 수준 도달")
    }

    @Test
    fun `보유 중 단계 상승과 전량 정리 후 단계 하락이 각 한 건씩 Discord 로 나간다`() {
        runScenario(job())

        assertScenarioRows()
    }

    @Test
    fun `이력 store 가 항상 예외여도 같은 단계 알림이 나간다`() {
        val failing = object : AccountPositionHistoryStore {
            override fun record(round: PositionHistoryRound) = throw IllegalStateException("history down")
        }
        runScenario(job(history = failing))

        assertScenarioRows()
    }

    @Test
    fun `부트스트랩 조회가 실패하면 단계 상태는 그대로이고 다른 알림과 결과는 같다`(output: CapturedOutput) {
        val broken = AccountReferencePositionReadPort { throw IllegalStateException("db down: 0.01 BTC") }
        val job = job(broken)

        round(job, "100000000", holding = false)
        assertThat(store.state.account.step.reference).isNull()
        assertThat(store.state.account.step.level).isNull()
        // 빗썸만 남는 회차는 헤지 불일치 포지션 변경 알림이 나가고 단계는 판정하지 않는다
        korea.quantity = BigDecimal("0.02")
        clock.advance(Duration.ofMinutes(1))
        assertThat(job.run()).isEqualTo(JobResult.Success)

        assertThat(rows("account-pnl-position-")).isNotEmpty()
        assertThat(rows("account-pnl-reference-step-")).isEmpty()
        assertThat(store.state.account.step.level).isNull()
        assertThat(output.all).contains("Account reference position read failed: error=IllegalStateException")
        assertThat(output.all).doesNotContain("db down").doesNotContain("0.01 BTC")
    }

    @Test
    fun `부트스트랩 조회가 취소면 interrupt 를 다시 세우고 회차는 저장하지 않는다`() {
        val cancelled = AccountReferencePositionReadPort { throw CancellationException("cancelled") }
        val job = job(cancelled)

        korea.quantity = BigDecimal.ZERO
        foreign.quantity = BigDecimal.ZERO
        clock.advance(Duration.ofMinutes(1))
        assertThat(job.run()).isEqualTo(JobResult.Skipped("cancelled"))

        assertThat(Thread.currentThread().isInterrupted).isTrue()
        assertThat(store.state.account.step.level).isNull()
    }

    @Test
    fun `부트스트랩 조회가 기준을 주면 상태를 FLAT 으로 잡고 알림은 없다`() {
        val reference = ReferenceLegs(BigDecimal("0.01"), BigDecimal("100000000"), BigDecimal("0.01"), BigDecimal("70000"))
        val job = job(AccountReferencePositionReadPort { reference })

        round(job, "100000000", holding = false)

        assertThat(store.state.account.step.phase).isEqualTo(ReferencePhase.FLAT)
        assertThat(store.state.account.step.level).isZero()
        assertThat(rows("account-pnl-reference-step-")).isEmpty()
    }

    @Test
    fun `port 가 없고 상태에 기준이 없는 FLAT 이면 단계 행이 없고 상태도 그대로다`() {
        val job = job(referencePositions = null)

        round(job, "100000000", holding = false)
        round(job, "98989899", holding = false)

        assertThat(rows("account-pnl-reference-step-")).isEmpty()
        assertThat(store.state.account.step.reference).isNull()
    }

    // ---- fakes ----

    private class Korea(private val clock: Clock) : KoreaSpotAccountReadPort {
        var bid = BigDecimal("100000000")
        var quantity = BigDecimal("0.01")

        override fun read(): KoreaSpotAccountSnapshot = KoreaSpotAccountSnapshot(
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

    private class Foreign(private val clock: Clock) : ForeignPerpAccountReadPort {
        var quantity = BigDecimal("-0.01")

        override fun read(): ForeignPerpAccountSnapshot = ForeignPerpAccountSnapshot(
            signedQuantity = quantity,
            entryPrice = BigDecimal("70000"),
            entryCommissionUsdt = BigDecimal.ZERO,
            fundingUsdt = BigDecimal.ZERO,
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
