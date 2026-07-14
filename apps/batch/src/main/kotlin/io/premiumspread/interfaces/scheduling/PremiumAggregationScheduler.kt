package io.premiumspread.interfaces.scheduling

import io.premiumspread.application.job.aggregation.PremiumAggregationJob
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
@ConditionalOnBatchScheduling
class PremiumAggregationScheduler(
    private val job: PremiumAggregationJob,
    @Suppress("unused") private val scheduling: BatchSchedulingProperties,
) {
    @Scheduled(fixedRateString = "\${batch.scheduling.premium-aggregation.summary-fixed-rate:10000}")
    fun updateSummaryCache() {
        job.updateSummary()
    }

    @Scheduled(cron = "\${batch.scheduling.premium-aggregation.minute-cron:0 * * * * *}")
    fun aggregateMinute() {
        job.aggregateMinute()
    }

    @Scheduled(cron = "\${batch.scheduling.premium-aggregation.hour-cron:5 0 * * * *}")
    fun aggregateHour() {
        job.aggregateHour()
    }

    @Scheduled(
        cron = "\${batch.scheduling.premium-aggregation.day-cron:10 0 0 * * *}",
        zone = "\${batch.scheduling.zone:Asia/Seoul}",
    )
    fun aggregateDay() {
        job.aggregateDay()
    }
}
