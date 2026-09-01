package io.premiumspread.interfaces.scheduling

import io.premiumspread.application.job.tradeprep.TradePreparationReconcileJob
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
@ConditionalOnBatchScheduling
class TradePreparationReconcileScheduler(
    private val job: TradePreparationReconcileJob,
    @Suppress("unused") private val scheduling: BatchSchedulingProperties,
) {
    @Scheduled(fixedRateString = "\${batch.scheduling.trade-preparation-reconcile.fixed-rate:60000}")
    fun reconcile() {
        job.run()
    }
}
