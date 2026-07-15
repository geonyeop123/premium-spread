package io.premiumspread.interfaces.scheduling

import io.premiumspread.application.job.ticker.BinanceTickerFlushJob
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
@ConditionalOnBatchScheduling
class BinanceFlushScheduler(
    private val job: BinanceTickerFlushJob,
    @Suppress("unused") private val scheduling: BatchSchedulingProperties,
) {
    @Scheduled(fixedRateString = "\${batch.scheduling.binance-flush.fixed-rate:1000}")
    fun flush() {
        job.run()
    }
}
