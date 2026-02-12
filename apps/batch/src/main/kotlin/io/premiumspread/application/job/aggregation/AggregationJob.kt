package io.premiumspread.application.job.aggregation

import io.premiumspread.application.common.JobResult
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.temporal.ChronoUnit

class AggregationJob<T>(
    private val reader: (from: Instant, to: Instant) -> T?,
    private val writer: (data: T, from: Instant, to: Instant) -> Unit,
    private val unit: ChronoUnit = ChronoUnit.MINUTES,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun run(): JobResult {
        return try {
            val now = Instant.now()
            val windowStart = now.minus(1, unit).truncatedTo(unit)
            val windowEnd = windowStart.plus(1, unit)

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
