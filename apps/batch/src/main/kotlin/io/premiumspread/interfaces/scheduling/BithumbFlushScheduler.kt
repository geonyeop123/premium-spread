package io.premiumspread.interfaces.scheduling

import io.premiumspread.application.job.ticker.BithumbTickerFlushJob
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
@ConditionalOnBatchScheduling
class BithumbFlushScheduler(
    private val job: BithumbTickerFlushJob,
    @Suppress("unused") private val scheduling: BatchSchedulingProperties,
) {
    @Scheduled(fixedRateString = "\${batch.scheduling.bithumb-flush.fixed-rate:1000}")
    fun flush() {
        job.run()
    }
}
