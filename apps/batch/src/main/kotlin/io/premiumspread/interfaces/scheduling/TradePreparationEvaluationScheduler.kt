package io.premiumspread.interfaces.scheduling

import io.premiumspread.application.job.tradeprep.TradePreparationEvaluationJob
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
@ConditionalOnBatchScheduling
class TradePreparationEvaluationScheduler(
    private val job: TradePreparationEvaluationJob,
    @Suppress("unused") private val scheduling: BatchSchedulingProperties,
) {
    @Scheduled(fixedRateString = "\${batch.scheduling.trade-preparation-evaluation.fixed-rate:1000}")
    fun evaluate() {
        job.run()
    }
}
