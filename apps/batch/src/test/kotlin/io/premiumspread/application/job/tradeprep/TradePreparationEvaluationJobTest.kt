package io.premiumspread.application.job.tradeprep

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.premiumspread.application.common.JobConfig
import io.premiumspread.application.common.JobExecutor
import io.premiumspread.application.common.JobResult
import io.premiumspread.domain.batch.BatchMarket
import io.premiumspread.domain.batch.BatchMarketProvider
import io.premiumspread.domain.job.JobId
import io.premiumspread.domain.market.MarketPair
import io.premiumspread.domain.premium.PremiumReadPort
import io.premiumspread.domain.premium.PremiumSnapshot
import io.premiumspread.domain.ticker.Currency
import io.premiumspread.domain.ticker.Exchange
import io.premiumspread.domain.ticker.Quote
import io.premiumspread.domain.ticker.Symbol
import io.premiumspread.domain.tradeprep.TradePreparationEvaluationOutcome
import io.premiumspread.domain.tradeprep.TradePreparationEvaluationService
import io.premiumspread.domain.tradeprep.TradePreparationEvaluationSummary
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Job 은 판정하지 않는다 — 읽기 port 와 Domain capability 를 조합하고 결과를 `JobResult` 로 옮길
 * 뿐이라는 것을 고정한다 (design.md D21).
 */
class TradePreparationEvaluationJobTest {

    private val premiumReader = mockk<PremiumReadPort>()
    private val evaluationService = mockk<TradePreparationEvaluationService>()
    private val marketProvider = mockk<BatchMarketProvider>()
    private val executor = mockk<JobExecutor>()
    private val now = Instant.parse("2026-08-30T00:00:10Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val pair = MarketPair.default(Symbol("BTC"))
    private lateinit var job: TradePreparationEvaluationJob

    @BeforeEach
    fun setUp() {
        every { executor.execute(any(), any()) } answers { secondArg<() -> JobResult>().invoke() }
        every { marketProvider.defaultMarket() } returns market()
        job = TradePreparationEvaluationJob(premiumReader, evaluationService, marketProvider, executor, clock)
    }

    @Test
    fun `설정된 market pair 의 현재 프리미엄과 주입한 clock 으로 Domain 평가를 호출한다`() {
        val snapshot = premium()
        every { premiumReader.findLatest(pair) } returns snapshot
        every { evaluationService.evaluate(pair, snapshot, now) } returns
            TradePreparationEvaluationSummary(TradePreparationEvaluationOutcome.EVALUATED, evaluated = 2, armed = 1)

        assertThat(job.run()).isEqualTo(JobResult.Success)

        verify(exactly = 1) { evaluationService.evaluate(pair, snapshot, now) }
    }

    @Test
    fun `현재 관측값이 없어도 Domain 이 판정하도록 그대로 넘긴다`() {
        every { premiumReader.findLatest(pair) } returns null
        every { evaluationService.evaluate(pair, null, now) } returns
            TradePreparationEvaluationSummary.notEvaluated(TradePreparationEvaluationOutcome.STREAM_UNAVAILABLE)

        val result = job.run()

        assertThat((result as JobResult.Skipped).reason).isEqualTo("stream_unavailable")
        verify(exactly = 1) { evaluationService.evaluate(pair, null, now) }
    }

    @Test
    fun `평가하지 못한 사유는 bounded skip 사유로 남는다`() {
        every { premiumReader.findLatest(pair) } returns premium()
        every { evaluationService.evaluate(any(), any(), any()) } returns
            TradePreparationEvaluationSummary.notEvaluated(TradePreparationEvaluationOutcome.STALE_OBSERVATION)

        assertThat((job.run() as JobResult.Skipped).reason).isEqualTo("stale_observation")
    }

    @Test
    fun `평가 중 예외는 Failure 로 보고한다`() {
        every { premiumReader.findLatest(pair) } returns premium()
        every { evaluationService.evaluate(any(), any(), any()) } throws IllegalStateException("db down")

        assertThat((job.run() as JobResult.Failure).exception).isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `typed JobConfig 로 lock 과 timeout 을 적용한다`() {
        val config = JobConfig(
            JobId.TRADE_PREPARATION_EVALUATION,
            "lock:custom",
            java.time.Duration.ofSeconds(9),
            java.time.Duration.ofSeconds(4),
        )
        val configured = TradePreparationEvaluationJob(
            premiumReader,
            evaluationService,
            marketProvider,
            executor,
            clock,
        ) { requested -> if (requested == JobId.TRADE_PREPARATION_EVALUATION) config else error("unexpected $requested") }
        every { premiumReader.findLatest(pair) } returns null
        every { evaluationService.evaluate(any(), any(), any()) } returns
            TradePreparationEvaluationSummary.notEvaluated(TradePreparationEvaluationOutcome.STREAM_UNAVAILABLE)

        configured.run()

        verify(exactly = 1) { executor.execute(config, any()) }
    }

    private fun premium(): PremiumSnapshot = PremiumSnapshot(
        pair = pair,
        premiumRate = BigDecimal("1.0000"),
        koreaPrice = BigDecimal("129555000"),
        foreignPrice = BigDecimal("89500"),
        foreignPriceInKrw = BigDecimal("128217700"),
        fxRate = BigDecimal("1432.6"),
        observedAt = now.minusSeconds(1),
    )

    private fun market(): BatchMarket {
        val symbol = Symbol("BTC")
        return BatchMarket(
            MarketPair.default(symbol),
            Quote.coin(symbol, Currency.KRW),
            Quote.coin(symbol, Currency.USD),
            Currency.USD,
            Currency.KRW,
        )
    }
}
