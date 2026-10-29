package io.premiumspread.application.job.ownermarket

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.premiumspread.application.common.JobConfig
import io.premiumspread.application.common.JobExecutor
import io.premiumspread.application.common.JobResult
import io.premiumspread.application.notification.owneralert.MutableClock
import io.premiumspread.domain.accountpnl.AccountPnlLatest
import io.premiumspread.domain.aggregation.AggregationUnit
import io.premiumspread.domain.aggregation.AggregationWindow
import io.premiumspread.domain.batch.BatchMarket
import io.premiumspread.domain.exchangerate.ExchangeRateSnapshot
import io.premiumspread.domain.job.JobId
import io.premiumspread.domain.market.MarketPair
import io.premiumspread.domain.owneralert.DiscordPush
import io.premiumspread.domain.owneralert.DiscordRoute
import io.premiumspread.domain.owneralert.DiscordTarget
import io.premiumspread.domain.owneralert.OwnerAlertChannel
import io.premiumspread.domain.owneralert.OwnerAlertChannels
import io.premiumspread.domain.owneralert.OwnerAlertKind
import io.premiumspread.domain.owneralert.OwnerAlertRouting
import io.premiumspread.domain.ownermarket.MarketAlertSettings
import io.premiumspread.domain.ownermarket.MarketAlertState
import io.premiumspread.domain.ownermarket.MarketDirection
import io.premiumspread.domain.ownermarket.MarketKeys
import io.premiumspread.domain.ownermarket.MarketWindows
import io.premiumspread.domain.ownermarket.PremiumZone
import io.premiumspread.domain.ownermarket.ThresholdBaseline
import io.premiumspread.domain.premium.PremiumAggregationSnapshot
import io.premiumspread.domain.premium.PremiumSnapshot
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
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CancellationException

/** owner 시장 신호 Job (design §7.4, dod AC12). */
@ExtendWith(OutputCaptureExtension::class)
class OwnerMarketSignalJobTest {
    private val zone = ZoneId.of("Asia/Seoul")

    /** 2026-10-29 06:00 KST - 요약 시각(09시) 전이다. */
    private val start = Instant.parse("2026-10-28T21:00:00Z")
    private val clock = MutableClock(start)
    private val btc = Symbol("BTC")
    private val pair = MarketPair.default(btc)
    private val pairKey = "btc-bithumb-binance"
    private var batchMarket = market(Currency.USD, Currency.KRW)
    private var marketFailure: Exception? = null
    private var routing = OwnerAlertRouting(OwnerAlertChannels(email = true, discord = true))
    private var settings = MarketAlertSettings()

    /** 현재값·집계가 모두 이 값이다(평평한 시장). */
    private var rate = BigDecimal("1.00")
    private var fullMinutes = true
    private val store = FakeOwnerMarketAlertStore()
    private val premiums = FakePremiumRead { snapshot(rate, clock.instant().minusSeconds(1), pair) }
    private val aggregations = FakeAggregationRead(
        minutes = { buckets(MarketWindows.of(clock.instant(), zone).minutes, Duration.ofMinutes(1)) },
        hours = { buckets(MarketWindows.of(clock.instant(), zone).hours, Duration.ofHours(1)) },
        day = { listOf(bucket(MarketWindows.of(clock.instant(), zone).previousDay.from)) },
    )
    private val fx = FakeFxRead { ExchangeRateSnapshot("USD", "KRW", BigDecimal("1385.20"), clock.instant()) }
    private val pnl = FakeAccountPnlLatest { AccountPnlLatest(false, null, null, clock.instant()) }
    private val executor = mockk<JobExecutor>()
    private val configSlot = slot<JobConfig>()

    init {
        every { executor.execute(capture(configSlot), any()) } answers { secondArg<() -> JobResult>().invoke() }
    }

    private fun market(fxBase: Currency, fxQuote: Currency) = BatchMarket(
        MarketPair.default(btc),
        Quote.coin(btc, Currency.KRW),
        Quote.coin(btc, Currency.USD),
        fxBase,
        fxQuote,
    )

    private fun job() = OwnerMarketSignalJob(
        premiums,
        aggregations,
        fx,
        pnl,
        { marketFailure?.let { throw it } ?: batchMarket },
        store,
        routing,
        executor,
        clock,
        settings,
    )

    private fun snapshot(value: BigDecimal, observedAt: Instant, forPair: MarketPair) = PremiumSnapshot(
        pair = forPair,
        premiumRate = value,
        koreaPrice = BigDecimal("100"),
        foreignPrice = BigDecimal("1"),
        foreignPriceInKrw = BigDecimal("99"),
        fxRate = BigDecimal("1385.20"),
        observedAt = observedAt,
    )

    private fun bucket(at: Instant) = PremiumAggregationSnapshot(
        pair, rate, rate, rate, rate, rate, 1, at, BigDecimal("1385.20"),
    )

    private fun buckets(window: AggregationWindow, step: Duration): List<PremiumAggregationSnapshot> {
        val all = generateSequence(window.from) { it.plus(step) }.takeWhile { it < window.to }.toList()
        val used = if (fullMinutes || step != Duration.ofMinutes(1)) all else all.takeLast(PARTIAL_MINUTES)
        return used.map(::bucket)
    }

    private fun key(name: String) = "market.$pairKey.$name"

    private fun fail(message: String = "secret-detail") = IllegalStateException(message)

    @Test
    fun `(a) 변화 없으면 enqueue 0회이고 Success 다`() {
        assertThat(job().run()).isEqualTo(JobResult.Success)

        assertThat(configSlot.captured.jobId).isEqualTo(JobId.OWNER_MARKET_SIGNAL)
        assertThat(store.enqueueCalls).isZero()
        assertThat(store.states).isEmpty()
    }

    @Test
    fun `(b) 급등이면 PREMIUM_SWING Discord MARKET SOUND 1행, 메일 없음, swing-up 상태`() {
        fullMinutes = false
        rate = BigDecimal("1.00")
        premiums.next = { snapshot(BigDecimal("1.60"), clock.instant().minusSeconds(1), pair) }

        assertThat(job().run()).isEqualTo(JobResult.Success)

        val row = store.rows.single()
        assertThat(row.channel).isEqualTo(OwnerAlertChannel.DISCORD)
        assertThat(row.discordRoute).isEqualTo(DiscordRoute(DiscordTarget.MARKET, DiscordPush.SOUND))
        assertThat(row.eventKey).isEqualTo("owner-market.$pairKey.swing-up.${start.toEpochMilli()}")
        val state = store.states.values.single()
        assertThat(state.signalKey).isEqualTo(key("swing-up"))
        assertThat(state.alertKind).isEqualTo(OwnerAlertKind.PREMIUM_SWING)
        assertThat(state.lastNotifiedAt).isEqualTo(start)
    }

    @Test
    fun `(c) 쿨다운 안 재조건은 0회이고 30분 뒤는 새 eventKey 행이다`() {
        fullMinutes = false
        premiums.next = { snapshot(BigDecimal("1.60"), clock.instant().minusSeconds(1), pair) }
        val job = job()
        job.run()

        clock.advance(Duration.ofMinutes(30).minusSeconds(1))
        assertThat(job.run()).isEqualTo(JobResult.Success)
        assertThat(store.enqueueCalls).isEqualTo(1)

        clock.advance(Duration.ofSeconds(1))
        assertThat(job.run()).isEqualTo(JobResult.Success)
        assertThat(store.enqueueCalls).isEqualTo(2)
        assertThat(store.rows.map { it.eventKey }).containsExactly(
            "owner-market.$pairKey.swing-up.${start.toEpochMilli()}",
            "owner-market.$pairKey.swing-up.${clock.instant().toEpochMilli()}",
        )
    }

    @Test
    fun `(d) 24h 신고가면 PREMIUM_NEW_EXTREME MARKET SILENT 1행과 상태 2개`() {
        premiums.next = { snapshot(BigDecimal("1.20"), clock.instant().minusSeconds(1), pair) }

        assertThat(job().run()).isEqualTo(JobResult.Success)

        val row = store.rows.single()
        assertThat(row.discordRoute).isEqualTo(DiscordRoute(DiscordTarget.MARKET, DiscordPush.SILENT))
        assertThat(row.eventKey).isEqualTo("owner-market.$pairKey.extreme-24h-high.${start.toEpochMilli()}")
        assertThat(store.states.keys).containsExactlyInAnyOrder(key("extreme-24h-high"), key("extreme-1h-high"))
    }

    @Test
    fun `(e) 기준선 env 가 없으면 threshold key 를 load 하지 않는다`() {
        job().run()

        val keys = store.loadedKeys.single()
        assertThat(keys).hasSize(7)
        assertThat(keys).noneMatch { it.endsWith(".threshold") }
        assertThat(keys).contains(key("swing-up"), key("swing-down"), key("daily-summary"))
    }

    @Test
    fun `(e) 기준선 U=3_0 이면 첫 회차 3_5 는 행 0 상태 1, 이후 2_8 2_95 3_0 에서 UPPER 행 1`() {
        settings = MarketAlertSettings(upperThreshold = BigDecimal("3.0"))
        val job = job()
        fun step(value: String) {
            rate = BigDecimal(value)
            premiums.next = { snapshot(rate, clock.instant().minusSeconds(1), pair) }
            clock.advance(Duration.ofSeconds(30))
            job.run()
        }

        step("3.5")
        assertThat(store.rows).isEmpty()
        assertThat(store.states.keys).containsExactly(key("threshold"))
        assertThat(store.loadedKeys.first()).contains(key("threshold"))

        step("2.8")
        step("2.95")
        assertThat(store.rows).isEmpty()

        step("3.0")
        val row = store.rows.single()
        assertThat(row.discordRoute).isEqualTo(DiscordRoute(DiscordTarget.MARKET, DiscordPush.SOUND))
        assertThat(row.eventKey).startsWith("owner-market.$pairKey.threshold-upper.")
    }

    @Test
    fun `(f) 09시 회차는 요약 EMAIL+DISCORD 2행이고 fxBase fxQuote 는 BatchMarket 값, 같은 날 다음 회차는 0행`() {
        batchMarket = market(Currency.KRW, Currency.USD)
        clock.advance(Duration.ofHours(3))
        val job = job()

        assertThat(job.run()).isEqualTo(JobResult.Success)

        assertThat(store.rows.map { it.channel }).containsExactly(OwnerAlertChannel.EMAIL, OwnerAlertChannel.DISCORD)
        assertThat(store.rows.map { it.eventKey }.toSet())
            .containsExactly("owner-market.$pairKey.daily-summary.2026-10-28")
        assertThat(store.rows.first().body).contains("KRW/USD")
        assertThat(store.states.keys).containsExactly(key("daily-summary"))

        clock.advance(Duration.ofSeconds(30))
        assertThat(job.run()).isEqualTo(JobResult.Success)
        assertThat(store.rows).hasSize(2)
        assertThat(store.enqueueCalls).isEqualTo(1)
    }

    @Test
    fun `(f) 08시59분59초 회차는 요약 입력을 읽지 않는다`() {
        clock.advance(Duration.ofHours(3).minusSeconds(1))

        assertThat(job().run()).isEqualTo(JobResult.Success)

        assertThat(aggregations.calls).doesNotContain(AggregationUnit.DAY)
        assertThat(fx.calls).isZero()
        assertThat(pnl.finds).isZero()
        assertThat(store.rows).isEmpty()
    }

    private fun summaryRun(breakSource: () -> Unit, source: String, output: CapturedOutput) {
        clock.advance(Duration.ofHours(3))
        fullMinutes = false
        premiums.next = { snapshot(BigDecimal("1.60"), clock.instant().minusSeconds(1), pair) }
        breakSource()
        val job = job()

        assertThat(job.run()).isEqualTo(JobResult.Success)

        assertThat(store.rows.map { it.eventKey }).allMatch { it.contains("swing-up") }
        assertThat(store.states.keys).containsExactly(key("swing-up"))
        assertThat(output.all).contains("Owner market observation failed: source=$source, error=IllegalStateException")

        aggregations.day = { listOf(bucket(MarketWindows.of(clock.instant(), zone).previousDay.from)) }
        fx.next = { ExchangeRateSnapshot("USD", "KRW", BigDecimal("1385.20"), clock.instant()) }
        pnl.latest = { null }
        clock.advance(Duration.ofSeconds(30))
        assertThat(job.run()).isEqualTo(JobResult.Success)

        assertThat(store.rows.filter { it.eventKey.contains("daily-summary") }).hasSize(2)
        assertThat(store.states.keys).contains(key("daily-summary"))
    }

    @Test
    fun `(g) 전일 집계 읽기 예외면 요약만 보류되고 다음 회차에 1통`(output: CapturedOutput) =
        summaryRun({ aggregations.day = { throw fail() } }, "day", output)

    @Test
    fun `(g) 환율 읽기 예외면 요약만 보류되고 다음 회차에 1통`(output: CapturedOutput) =
        summaryRun({ fx.next = { throw fail() } }, "fx", output)

    @Test
    fun `(g) 실계정 읽기 예외면 요약만 보류되고 다음 회차에 1통`(output: CapturedOutput) =
        summaryRun({ pnl.latest = { throw fail() } }, "pnl", output)

    @Test
    fun `(h) 현재값 없음 11초 지남 다른 pair 는 현재값 의존 신호가 0행이고 상태 무변경`() {
        settings = MarketAlertSettings(upperThreshold = BigDecimal("3.0"))
        val other = MarketPair(btc, Exchange.UPBIT, Exchange.BINANCE)
        val cases = listOf<() -> PremiumSnapshot?>(
            { null },
            { snapshot(BigDecimal("9.00"), clock.instant().minusSeconds(11), pair) },
            { snapshot(BigDecimal("9.00"), clock.instant().minusSeconds(1), other) },
        )
        cases.forEach { case ->
            premiums.next = { case() }
            assertThat(job().run()).isEqualTo(JobResult.Success)
        }

        assertThat(store.rows).isEmpty()
        assertThat(store.states).isEmpty()
        assertThat(store.enqueueCalls).isZero()
    }

    @Test
    fun `(h) 분 읽기 예외면 급변 1h 24h 가 모두 빠진다`(output: CapturedOutput) {
        premiums.next = { snapshot(BigDecimal("1.60"), clock.instant().minusSeconds(1), pair) }
        aggregations.minutes = { throw fail() }

        assertThat(job().run()).isEqualTo(JobResult.Success)

        assertThat(store.rows).isEmpty()
        assertThat(store.enqueueCalls).isZero()
        assertThat(output.all).contains("Owner market observation failed: source=minutes, error=IllegalStateException")
    }

    @Test
    fun `(h) 시 읽기 예외면 24h 만 빠지고 1h 는 판정한다`(output: CapturedOutput) {
        premiums.next = { snapshot(BigDecimal("1.20"), clock.instant().minusSeconds(1), pair) }
        aggregations.hours = { throw fail() }

        assertThat(job().run()).isEqualTo(JobResult.Success)

        assertThat(store.rows.single().eventKey).isEqualTo("owner-market.$pairKey.extreme-1h-high.${start.toEpochMilli()}")
        assertThat(store.states.keys).containsExactly(key("extreme-1h-high"))
        assertThat(output.all).contains("Owner market observation failed: source=hours, error=IllegalStateException")
    }

    @Test
    fun `(i) 새 Job 인스턴스도 저장된 쿨다운을 유지한다`() {
        fullMinutes = false
        premiums.next = { snapshot(BigDecimal("1.60"), clock.instant().minusSeconds(1), pair) }
        job().run()

        clock.advance(Duration.ofMinutes(5))
        assertThat(job().run()).isEqualTo(JobResult.Success)

        assertThat(store.enqueueCalls).isEqualTo(1)
        assertThat(store.rows).hasSize(1)
    }

    @Test
    fun `(j) load 예외와 defaultMarket 예외는 Failure 다`(output: CapturedOutput) {
        val loadFailure = fail("db down")
        store.loadFailure = loadFailure
        assertThat(job().run()).isEqualTo(JobResult.Failure(loadFailure))
        assertThat(output.all).contains("Owner market signal store failed during load")

        store.loadFailure = null
        val marketError = fail("no market")
        marketFailure = marketError
        assertThat(job().run()).isEqualTo(JobResult.Failure(marketError))
        assertThat(output.all).contains("Owner market signal store failed during defaultMarket")
    }

    private fun swingScenario() {
        fullMinutes = false
        premiums.next = { snapshot(BigDecimal("1.60"), clock.instant().minusSeconds(1), pair) }
    }

    @Test
    fun `(j) enqueue 예외면 Failure 다`(output: CapturedOutput) {
        swingScenario()
        val failure = fail("db down")
        store.nextEnqueueFailure = failure

        assertThat(job().run()).isEqualTo(JobResult.Failure(failure))
        assertThat(output.all).contains("Owner market signal store failed during enqueue")
    }

    @Test
    fun `(j) enqueue false 면 Skipped state-version-conflict 다`(output: CapturedOutput) {
        swingScenario()
        store.nextEnqueueConflict = true

        assertThat(job().run()).isEqualTo(JobResult.Skipped("state-version-conflict"))
        assertThat(store.enqueueCalls).isEqualTo(1)
        assertThat(output.all).contains("Skipped owner market signal: signal state version changed by another run")
    }

    @Test
    fun `(j) 저장 전 interrupt 면 Skipped cancelled 이고 쓰지 않는다`(output: CapturedOutput) {
        swingScenario()
        premiums.next = {
            Thread.currentThread().interrupt()
            snapshot(BigDecimal("1.60"), clock.instant().minusSeconds(1), pair)
        }

        try {
            assertThat(job().run()).isEqualTo(JobResult.Skipped("cancelled"))
            assertThat(store.enqueueCalls).isZero()
            assertThat(output.all).contains("Owner market signal was cancelled before saving state")
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `(k) 원천이 InterruptedException 을 감싸 던지면 다시 던지고 interrupt 를 세우며 쓰지 않는다`() {
        swingScenario()
        val failure = RuntimeException(InterruptedException())
        aggregations.hours = { throw failure }

        try {
            val thrown = assertThrows<RuntimeException> { job().run() }

            assertThat(thrown).isSameAs(failure)
            assertThat(Thread.currentThread().isInterrupted).isTrue()
            assertThat(store.enqueueCalls).isZero()
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `(k) 요약 입력의 CancellationException 도 다시 던진다`() {
        clock.advance(Duration.ofHours(3))
        val failure = CancellationException("cancelled")
        fx.next = { throw failure }

        try {
            val thrown = assertThrows<CancellationException> { job().run() }

            assertThat(thrown).isSameAs(failure)
            assertThat(Thread.currentThread().isInterrupted).isTrue()
            assertThat(store.enqueueCalls).isZero()
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `(l) EMAIL_ONLY 면 4종 모두 EMAIL 1행이다`() {
        routing = OwnerAlertRouting.EMAIL_ONLY
        settings = MarketAlertSettings(upperThreshold = BigDecimal("3.0"))
        clock.advance(Duration.ofHours(3))
        store.states[key("threshold")] = MarketAlertState(
            key("threshold"),
            OwnerAlertKind.PREMIUM_THRESHOLD_CROSSED,
            null,
            PremiumZone.BETWEEN,
            ThresholdBaseline(BigDecimal("3.0"), null, BigDecimal("0.1")),
            null,
            1,
        )
        premiums.next = { snapshot(BigDecimal("3.20"), clock.instant().minusSeconds(1), pair) }

        assertThat(job().run()).isEqualTo(JobResult.Success)

        assertThat(store.rows.map { it.channel }.toSet()).containsExactly(OwnerAlertChannel.EMAIL)
        assertThat(store.rows.map { it.eventKey.substringAfter("$pairKey.").substringBeforeLast('.') }.toSet())
            .contains("swing-up", "threshold-upper", "extreme-24h-high")
        assertThat(store.rows.map { it.eventKey }).anyMatch { it.contains("daily-summary") }
        assertThat(store.rows).hasSize(4)
    }

    @Test
    fun `(m) 로그에 주소 예외 메시지 수치 금액이 없다`(output: CapturedOutput) {
        clock.advance(Duration.ofHours(3))
        fullMinutes = false
        rate = BigDecimal("1.00")
        premiums.next = { snapshot(BigDecimal("2.50"), clock.instant().minusSeconds(1), pair) }
        val secret = "secret-detail owner@example.test https://discord.example.test/api/webhooks/1/x"
        pnl.latest = { throw fail(secret) }
        aggregations.hours = { throw fail(secret) }

        assertThat(job().run()).isEqualTo(JobResult.Success)

        assertThat(output.all).contains("Owner market alert decided: kind=PREMIUM_SWING, eventKey=owner-market.")
        assertThat(output.all).contains("source=pnl, error=IllegalStateException")
        assertThat(output.all).doesNotContain("example.test", "@", "secret-detail", "2.50", "원")
    }

    @Test
    fun `상태 key 는 MarketKeys 와 같다`() {
        job().run()

        assertThat(store.loadedKeys.single()).contains(
            MarketKeys.swing(pairKey, MarketDirection.UP),
            MarketKeys.dailySummary(pairKey),
        )
    }

    private companion object {
        const val PARTIAL_MINUTES = 10
    }
}
