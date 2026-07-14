package io.premiumspread.interfaces.scheduling

import io.premiumspread.application.job.aggregation.TickerAggregationJob
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
@ConditionalOnBatchScheduling
class TickerAggregationScheduler(
    private val job: TickerAggregationJob,
    @Suppress("unused") private val scheduling: BatchSchedulingProperties,
) {
    @Scheduled(cron = "\${batch.scheduling.ticker-aggregation.minute-cron:2 * * * * *}")
    fun aggregateMinute() {
        job.aggregateMinute()
    }

    @Scheduled(cron = "\${batch.scheduling.ticker-aggregation.hour-cron:7 0 * * * *}")
    fun aggregateHour() {
        job.aggregateHour()
    }

    @Scheduled(
        cron = "\${batch.scheduling.ticker-aggregation.day-cron:12 0 0 * * *}",
        zone = "\${batch.scheduling.zone:Asia/Seoul}",
    )
    fun aggregateDay() {
        job.aggregateDay()
    }
}
