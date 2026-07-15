package io.premiumspread.interfaces.scheduling

import io.premiumspread.application.job.premium.PremiumRealtimeJob
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
@ConditionalOnBatchScheduling
class PremiumScheduler(
    private val job: PremiumRealtimeJob,
    @Suppress("unused") private val scheduling: BatchSchedulingProperties,
) {
    @Scheduled(fixedRateString = "\${batch.scheduling.premium.fixed-rate:1000}")
    fun calculatePremium() {
        job.run()
    }
}
