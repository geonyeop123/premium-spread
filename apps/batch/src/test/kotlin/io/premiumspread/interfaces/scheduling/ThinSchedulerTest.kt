package io.premiumspread.interfaces.scheduling

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.premiumspread.application.common.JobResult
import io.premiumspread.application.job.aggregation.PremiumAggregationJob
import io.premiumspread.application.job.aggregation.TickerAggregationJob
import io.premiumspread.application.job.fx.FxIngestionJob
import io.premiumspread.application.job.premium.PremiumRealtimeJob
import io.premiumspread.application.job.ticker.BinanceTickerFlushJob
import io.premiumspread.application.job.ticker.BithumbTickerFlushJob
import io.premiumspread.application.job.tradeprep.TradePreparationEvaluationJob
import org.junit.jupiter.api.Test

class ThinSchedulerTest {
    private val scheduling = BatchSchedulingProperties()

    @Test
    fun `exchange rate scheduler는 application job만 호출한다`() {
        val job = mockk<FxIngestionJob>()
        every { job.run() } returns JobResult.Success
        ExchangeRateScheduler(job, scheduling).fetchExchangeRate()
        verify(exactly = 1) { job.run() }
    }

    @Test
    fun `premium scheduler는 application job만 호출한다`() {
        val job = mockk<PremiumRealtimeJob>()
        every { job.run() } returns JobResult.Success
        PremiumScheduler(job, scheduling).calculatePremium()
        verify(exactly = 1) { job.run() }
    }

    @Test
    fun `premium aggregation scheduler의 각 trigger는 같은 application job에 위임한다`() {
        val job = mockk<PremiumAggregationJob>(relaxed = true)
        val scheduler = PremiumAggregationScheduler(job, scheduling)
        scheduler.updateSummaryCache()
        scheduler.aggregateMinute()
        scheduler.aggregateHour()
        scheduler.aggregateDay()
        verify(exactly = 1) { job.updateSummary() }
        verify(exactly = 1) { job.aggregateMinute() }
        verify(exactly = 1) { job.aggregateHour() }
        verify(exactly = 1) { job.aggregateDay() }
    }

    @Test
    fun `ticker aggregation scheduler의 각 trigger는 같은 application job에 위임한다`() {
        val job = mockk<TickerAggregationJob>(relaxed = true)
        val scheduler = TickerAggregationScheduler(job, scheduling)
        scheduler.aggregateMinute()
        scheduler.aggregateHour()
        scheduler.aggregateDay()
        verify(exactly = 1) { job.aggregateMinute() }
        verify(exactly = 1) { job.aggregateHour() }
        verify(exactly = 1) { job.aggregateDay() }
    }

    @Test
    fun `trade preparation evaluation scheduler는 application job만 호출한다`() {
        val job = mockk<TradePreparationEvaluationJob>()
        every { job.run() } returns JobResult.Success
        TradePreparationEvaluationScheduler(job, scheduling).evaluate()
        verify(exactly = 1) { job.run() }
    }

    @Test
    fun `flush scheduler는 exchange별 application job 하나에 위임한다`() {
        val binance = mockk<BinanceTickerFlushJob>(relaxed = true)
        val bithumb = mockk<BithumbTickerFlushJob>(relaxed = true)
        BinanceFlushScheduler(binance, scheduling).flush()
        BithumbFlushScheduler(bithumb, scheduling).flush()
        verify(exactly = 1) { binance.run() }
        verify(exactly = 1) { bithumb.run() }
    }
}
