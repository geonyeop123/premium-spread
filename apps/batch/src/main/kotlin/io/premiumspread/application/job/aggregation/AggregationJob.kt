package io.premiumspread.application.job.aggregation

import io.premiumspread.application.common.JobResult
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit

class AggregationJob<T>(
    private val reader: (from: Instant, to: Instant) -> T?,
    private val writer: (data: T, from: Instant, to: Instant) -> Unit,
    private val unit: ChronoUnit = ChronoUnit.MINUTES,
    private val clock: Clock,
    private val windowPolicy: AggregationWindowPolicy,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun run(): JobResult {
        return try {
            val window = windowPolicy.previous(clock.instant(), unit)
            val windowStart = window.from
            val windowEnd = window.to

            val data = reader(windowStart, windowEnd)

            if (data == null) {
                log.warn("No data to aggregate for {} at {}", unit, windowStart)
                return JobResult.Skipped("no_data")
            }

            writer(data, windowStart, windowEnd)
            JobResult.Success
        } catch (e: Exception) {
            log.error("Aggregation failed", e)
            JobResult.Failure(e)
        }
    }
}
