package io.premiumspread.application.job.ownerops

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.premiumspread.application.common.JobConfig
import io.premiumspread.application.common.JobExecutor
import io.premiumspread.application.common.JobResult
import io.premiumspread.application.notification.owneralert.MutableClock
import io.premiumspread.domain.accountpnl.OwnerAlertIncident
import io.premiumspread.domain.accountpnl.OwnerAlertSignalState
import io.premiumspread.domain.batch.BatchMarket
import io.premiumspread.domain.job.JobFailureStreak
import io.premiumspread.domain.job.JobId
import io.premiumspread.domain.market.MarketPair
import io.premiumspread.domain.owneralert.DiscordPush
import io.premiumspread.domain.owneralert.DiscordRoute
import io.premiumspread.domain.owneralert.DiscordTarget
import io.premiumspread.domain.owneralert.OwnerAlertChannel
import io.premiumspread.domain.owneralert.OwnerAlertChannels
import io.premiumspread.domain.owneralert.OwnerAlertKind
import io.premiumspread.domain.owneralert.OwnerAlertRouting
import io.premiumspread.domain.ticker.Currency
import io.premiumspread.domain.ticker.Exchange
import io.premiumspread.domain.ticker.Quote
import io.premiumspread.domain.ticker.Symbol
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CancellationException

/** owner 운영 신호 Job (design §8.2, dod AC8). */
@ExtendWith(OutputCaptureExtension::class)
class OwnerOpsSignalJobTest {
    private val start = Instant.parse("2026-10-28T00:00:00Z")
    private val clock = MutableClock(start)
    private val outbox = FakeOwnerAlertOutbox()
    private val lastSuccess = FakeLastSuccess(clock)
    private val streaks = FakeFailureStreaks()
    private val ticks = FakeTicks(clock)
    private val executor = mockk<JobExecutor>()
    private val configSlot = slot<JobConfig>()
    private val btc = Symbol("BTC")
    private val koreaQuote = Quote.coin(btc, Currency.KRW)
    private val foreignQuote = Quote.coin(btc, Currency.USD)
    private var batchMarket = market(Currency.USD, Currency.KRW)
    private var marketFailure: Exception? = null
    private var routing = OwnerAlertRouting(OwnerAlertChannels(email = true, discord = true))

    init {
        every { executor.execute(capture(configSlot), any()) } answers { secondArg<() -> JobResult>().invoke() }
    }

    private fun market(fxBase: Currency, fxQuote: Currency) =
        BatchMarket(MarketPair.default(btc), koreaQuote, foreignQuote, fxBase, fxQuote)

    private fun job() = OwnerOpsSignalJob(
        lastSuccess,
        streaks,
        ticks,
        { marketFailure?.let { throw it } ?: batchMarket },
        outbox,
        routing,
        executor,
        clock,
    )

    private fun fxDown() {
        lastSuccess.overrides[JobId.FX_INGESTION] = start.minus(Duration.ofMinutes(31))
    }

    private fun streak(jobId: JobId, count: Int, firstFailureAt: Instant) =
        JobFailureStreak(jobId, count, firstFailureAt, clock.instant())

    @Test
    fun `(a) 모두 정상이면 enqueue 0회이고 Success 다`() {
        assertThat(job().run()).isEqualTo(JobResult.Success)

        assertThat(configSlot.captured.jobId).isEqualTo(JobId.OWNER_OPS_SIGNAL)
        assertThat(outbox.enqueueCalls).isZero()
        assertThat(outbox.states).isEmpty()
    }

    @Test
    fun `(b) FX 마지막 성공 31분 전이면 FX_INGESTION_DELAYED Discord 1행과 신호 상태 1개`() {
        fxDown()

        assertThat(job().run()).isEqualTo(JobResult.Success)

        val row = outbox.rows.single()
        assertThat(row.channel).isEqualTo(OwnerAlertChannel.DISCORD)
        assertThat(row.discordRoute).isEqualTo(DiscordRoute(DiscordTarget.OPS, DiscordPush.SOUND))
        assertThat(row.eventKey).isEqualTo("owner-ops.fx-ingestion.usd-krw.${start.toEpochMilli()}.alert-1")
        val state = outbox.states.values.single()
        assertThat(state.signalKey).isEqualTo("fx-ingestion.usd-krw")
        assertThat(state.alertKind).isEqualTo(OwnerAlertKind.FX_INGESTION_DELAYED)
        assertThat(state.incident).isEqualTo(OwnerAlertIncident(start, start, 1))
    }

    @Test
    fun `(b) FX 신호 key 는 BatchMarket 의 fxBase·fxQuote 에서 온다`() {
        batchMarket = market(Currency.KRW, Currency.USD)
        fxDown()

        job().run()

        assertThat(outbox.states.keys).containsExactly("fx-ingestion.krw-usd")
    }

    @Test
    fun `(c) 바이낸스 tick 6분 전이면 MARKET_STREAM_DOWN 1행이고 조회 쌍은 BatchMarket 두 쌍뿐이다`() {
        ticks.overrides[Exchange.BINANCE to foreignQuote] = start.minus(Duration.ofMinutes(6))

        assertThat(job().run()).isEqualTo(JobResult.Success)

        val row = outbox.rows.single()
        assertThat(row.eventKey).startsWith("owner-ops.market-stream.binance.btc-usd.")
        assertThat(row.discordRoute).isEqualTo(DiscordRoute(DiscordTarget.OPS, DiscordPush.SOUND))
        assertThat(outbox.states.values.single().alertKind).isEqualTo(OwnerAlertKind.MARKET_STREAM_DOWN)
        assertThat(ticks.calls.toSet())
            .containsExactlyInAnyOrder(Exchange.BITHUMB to koreaQuote, Exchange.BINANCE to foreignQuote)
    }

    @Test
    fun `(d) PREMIUM_REALTIME 3회·5분 연속 실패면 메일+Discord 2행, 자기 자신과 전달 Job 은 감시하지 않는다`() {
        streaks.streaks[JobId.PREMIUM_REALTIME] = streak(JobId.PREMIUM_REALTIME, 3, start.minus(Duration.ofMinutes(5)))
        streaks.streaks[JobId.OWNER_OPS_SIGNAL] = streak(JobId.OWNER_OPS_SIGNAL, 9, start.minus(Duration.ofHours(1)))
        streaks.streaks[JobId.OWNER_ALERT_DELIVERY] =
            streak(JobId.OWNER_ALERT_DELIVERY, 9, start.minus(Duration.ofHours(1)))

        assertThat(job().run()).isEqualTo(JobResult.Success)

        assertThat(outbox.rows.map { it.channel }).containsExactly(OwnerAlertChannel.EMAIL, OwnerAlertChannel.DISCORD)
        assertThat(outbox.rows.map { it.eventKey }.toSet())
            .containsExactly("owner-ops.job-failures.premium_realtime.${start.toEpochMilli()}.alert-1")
        assertThat(outbox.states.keys).containsExactly("job-failures.premium_realtime")
        assertThat(streaks.calls).containsExactlyElementsOf(OwnerOpsSignalJob.MONITORED_JOBS)
        assertThat(streaks.calls).doesNotContain(JobId.OWNER_OPS_SIGNAL, JobId.OWNER_ALERT_DELIVERY)
    }

    @Test
    fun `(e) 같은 장애는 6시간 뒤 alert-2 이고 그 사이 회차는 enqueue 0회다`() {
        fxDown()
        val job = job()

        job.run()
        repeat((Duration.ofHours(6).seconds / 30).toInt()) {
            clock.advance(Duration.ofSeconds(30))
            assertThat(job.run()).isEqualTo(JobResult.Success)
        }

        assertThat(outbox.enqueueCalls).isEqualTo(2)
        val base = "owner-ops.fx-ingestion.usd-krw.${start.toEpochMilli()}"
        assertThat(outbox.rows.map { it.eventKey }).containsExactly("$base.alert-1", "$base.alert-2")
        assertThat(outbox.states.keys).containsExactly("fx-ingestion.usd-krw")
    }

    @Test
    fun `(f) 해소되면 OPS_RECOVERED Discord SILENT 1행이고 상태가 정상으로 돌아간다`() {
        fxDown()
        val job = job()
        job.run()
        lastSuccess.overrides.remove(JobId.FX_INGESTION)
        clock.advance(Duration.ofSeconds(30))

        assertThat(job.run()).isEqualTo(JobResult.Success)

        val row = outbox.rows.last()
        assertThat(outbox.rows).hasSize(2)
        assertThat(row.channel).isEqualTo(OwnerAlertChannel.DISCORD)
        assertThat(row.discordRoute).isEqualTo(DiscordRoute(DiscordTarget.OPS, DiscordPush.SILENT))
        assertThat(row.eventKey).isEqualTo("owner-ops.fx-ingestion.usd-krw.${start.toEpochMilli()}.recovered")
        assertThat(outbox.states.getValue("fx-ingestion.usd-krw").incident).isNull()
    }

    @Test
    fun `(g) 재시작 뒤 저장된 FX 장애와 성공 기록 없음이면 30분 동안 enqueue 0회다`() {
        outbox.states["fx-ingestion.usd-krw"] = OwnerAlertSignalState(
            "fx-ingestion.usd-krw",
            OwnerAlertKind.FX_INGESTION_DELAYED,
            OwnerAlertIncident(start, start, 1),
            1,
        )
        lastSuccess.overrides[JobId.FX_INGESTION] = null
        val job = job()

        repeat(61) {
            assertThat(job.run()).isEqualTo(JobResult.Success)
            clock.advance(Duration.ofSeconds(30))
        }

        assertThat(outbox.enqueueCalls).isZero()
        assertThat(outbox.states.getValue("fx-ingestion.usd-krw").incident).isNotNull()
    }

    /** 같은 인스턴스를 [start] 로부터 [seconds] 초 시점에 실행한다. */
    private fun OwnerOpsSignalJob.runAfter(seconds: Long): JobResult {
        val target = start.plusSeconds(seconds)
        clock.advance(Duration.between(clock.instant(), target))
        return run()
    }

    @Test
    fun `(g) 시세 tick 이 계속 없으면 첫 실행 시각이 유지되어 4분 59초까지 0회이고 5분 회차에 1건이다`() {
        ticks.overrides[Exchange.BINANCE to foreignQuote] = null
        val job = job()

        (0L..270L step 30L).forEach { assertThat(job.runAfter(it)).isEqualTo(JobResult.Success) }
        assertThat(job.runAfter(299)).isEqualTo(JobResult.Success)
        assertThat(outbox.enqueueCalls).isZero()

        assertThat(job.runAfter(300)).isEqualTo(JobResult.Success)

        assertThat(outbox.enqueueCalls).isEqualTo(1)
        assertThat(outbox.rows.single().eventKey).startsWith("owner-ops.market-stream.binance.btc-usd.")
    }

    @Test
    fun `(g) FX 성공 기록이 계속 없으면 첫 실행 시각이 유지되어 30분까지 0회이고 30분 30초 회차에 1건이다`() {
        lastSuccess.overrides[JobId.FX_INGESTION] = null
        val job = job()

        (0L..1800L step 30L).forEach { assertThat(job.runAfter(it)).isEqualTo(JobResult.Success) }
        assertThat(outbox.enqueueCalls).isZero()

        assertThat(job.runAfter(1830)).isEqualTo(JobResult.Success)

        assertThat(outbox.enqueueCalls).isEqualTo(1)
        assertThat(outbox.rows.single().eventKey).startsWith("owner-ops.fx-ingestion.usd-krw.")
    }

    @Test
    fun `(g) 요청과 다른 quote 의 tick 은 근거로 쓰지 않아 tick 없음과 같이 5분 뒤 Down 이다`() {
        ticks.answerQuotes[Exchange.BINANCE to foreignQuote] = Quote.coin(btc, Currency.KRW)
        val job = job()

        assertThat(job.runAfter(0)).isEqualTo(JobResult.Success)
        assertThat(job.runAfter(299)).isEqualTo(JobResult.Success)
        assertThat(outbox.enqueueCalls).isZero()

        assertThat(job.runAfter(300)).isEqualTo(JobResult.Success)

        assertThat(outbox.rows.single().eventKey).startsWith("owner-ops.market-stream.binance.btc-usd.")
    }

    @Test
    fun `(h) EMAIL_ONLY 면 운영 알림 4종 모두 EMAIL 1행이다`() {
        routing = OwnerAlertRouting(OwnerAlertChannels(email = true, discord = false))
        fxDown()
        ticks.overrides[Exchange.BITHUMB to koreaQuote] = start.minus(Duration.ofMinutes(6))
        streaks.streaks[JobId.PREMIUM_REALTIME] = streak(JobId.PREMIUM_REALTIME, 3, start.minus(Duration.ofMinutes(5)))
        val job = job()
        job.run()
        lastSuccess.overrides.remove(JobId.FX_INGESTION)
        clock.advance(Duration.ofSeconds(30))
        job.run()

        assertThat(outbox.rows).allMatch { it.channel == OwnerAlertChannel.EMAIL && it.discordRoute == null }
        assertThat(outbox.rows.map { it.eventKey.split(".").take(2).joinToString(".") })
            .containsExactly(
                "owner-ops.fx-ingestion",
                "owner-ops.market-stream",
                "owner-ops.job-failures",
                "owner-ops.fx-ingestion",
            )
        assertThat(outbox.rows.last().eventKey).endsWith(".recovered")
        assertThat(outbox.rows.map { it.eventKey }).doesNotHaveDuplicates()
    }

    @Test
    fun `(i) defaultMarket 예외면 Failure 이고 저장하지 않는다`(output: CapturedOutput) {
        marketFailure = IllegalStateException("market down")

        val result = job().run()

        assertThat(result).isInstanceOf(JobResult.Failure::class.java)
        assertThat(outbox.enqueueCalls).isZero()
        assertThat(output.all).contains("Owner ops signal store failed during defaultMarket")
    }

    @Test
    fun `(i) loadSignals 예외면 Failure 다`(output: CapturedOutput) {
        val failure = IllegalStateException("db down")
        outbox.loadFailure = failure
        fxDown()

        val result = job().run()

        assertThat(result).isEqualTo(JobResult.Failure(failure))
        assertThat(outbox.enqueueCalls).isZero()
        assertThat(output.all).contains("Owner ops signal store failed during loadSignals")
    }

    @Test
    fun `(i) enqueue 예외면 Failure 다`(output: CapturedOutput) {
        val failure = IllegalStateException("db down")
        outbox.nextEnqueueFailure = failure
        fxDown()

        val result = job().run()

        assertThat(result).isEqualTo(JobResult.Failure(failure))
        assertThat(output.all).contains("Owner ops signal store failed during enqueue")
    }

    @Test
    fun `(i) enqueue false 면 Skipped state-version-conflict 다`(output: CapturedOutput) {
        outbox.nextEnqueueConflict = true
        fxDown()

        assertThat(job().run()).isEqualTo(JobResult.Skipped("state-version-conflict"))
        assertThat(outbox.enqueueCalls).isEqualTo(1)
        assertThat(output.all).contains("Skipped owner ops signal: signal state version changed by another run")
    }

    @Test
    fun `(i) 저장 전 interrupt 면 Skipped cancelled 이고 쓰지 않는다`(output: CapturedOutput) {
        fxDown()
        val lastJob = OwnerOpsSignalJob.MONITORED_JOBS.last()
        streaks.onCall = { if (it == lastJob) Thread.currentThread().interrupt() }

        try {
            assertThat(job().run()).isEqualTo(JobResult.Skipped("cancelled"))
            assertThat(outbox.enqueueCalls).isZero()
            assertThat(output.all).contains("Owner ops signal was cancelled before saving state")
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `(i) 한 신호 관측 예외면 그 신호만 Unknown 이고 나머지는 판정되어 Success 다`(output: CapturedOutput) {
        fxDown()
        ticks.failures[Exchange.BITHUMB to koreaQuote] = IllegalStateException("tick read failed")

        assertThat(job().run()).isEqualTo(JobResult.Success)

        assertThat(outbox.states.keys).containsExactly("fx-ingestion.usd-krw")
        assertThat(ticks.calls).contains(Exchange.BINANCE to foreignQuote)
        assertThat(streaks.calls).containsExactlyElementsOf(OwnerOpsSignalJob.MONITORED_JOBS)
        assertThat(output.all).contains(
            "Owner ops signal observation failed: signal=market-stream.bithumb.btc-krw, error=IllegalStateException",
        )
    }

    @Test
    fun `(j) 로그에 수신 주소·Discord 주소·관측 예외 메시지가 없다`(output: CapturedOutput) {
        val secret = "observation boom owner@example.test https://discord.example.test/api/webhooks/1/x"
        ticks.failures[Exchange.BITHUMB to koreaQuote] = IllegalStateException(secret)
        streaks.streaks[JobId.PREMIUM_REALTIME] = streak(JobId.PREMIUM_REALTIME, 3, start.minus(Duration.ofMinutes(5)))

        assertThat(job().run()).isEqualTo(JobResult.Success)

        assertThat(output.all).contains(
            "Owner ops alert decided: signal=job-failures.premium_realtime, notice=alert, sequence=1, " +
                "eventKey=owner-ops.job-failures.premium_realtime.${start.toEpochMilli()}.alert-1",
        )
        assertThat(output.all).doesNotContain("example.test", "owner@", "observation boom")
    }

    @Test
    fun `(k) 관측 예외 원인에 InterruptedException 이 있으면 다시 던지고 interrupt 를 세우며 쓰지 않는다`() {
        fxDown()
        val failure = RuntimeException(InterruptedException())
        ticks.failures[Exchange.BINANCE to foreignQuote] = failure

        try {
            val thrown = assertThrows<RuntimeException> { job().run() }

            assertThat(thrown).isSameAs(failure)
            assertThat(Thread.currentThread().isInterrupted).isTrue()
            assertThat(outbox.enqueueCalls).isZero()
            assertThat(streaks.calls).isEmpty()
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `(k) 관측 port 가 CancellationException 을 던져도 다시 던진다`() {
        fxDown()
        val failure = CancellationException("cancelled")
        streaks.failures[JobId.FX_INGESTION] = failure

        try {
            val thrown = assertThrows<CancellationException> { job().run() }

            assertThat(thrown).isSameAs(failure)
            assertThat(Thread.currentThread().isInterrupted).isTrue()
            assertThat(outbox.enqueueCalls).isZero()
        } finally {
            Thread.interrupted()
        }
    }
}
