package io.premiumspread.application.common

import io.premiumspread.domain.job.JobId
import java.time.Duration

data class JobConfig(val jobId: JobId, val lockKey: String, val lease: Duration, val executionTimeout: Duration) {
    init {
        require(lockKey.isNotBlank()) { "lockKey must not be blank." }
        require(!lease.isZero && !lease.isNegative) { "lease must be positive." }
        require(!executionTimeout.isZero && !executionTimeout.isNegative) { "executionTimeout must be positive." }
        require(lease > executionTimeout) { "lease must exceed executionTimeout." }
    }
}

fun interface JobConfigProvider {
    fun get(jobId: JobId): JobConfig
}

object DefaultJobConfigProvider : JobConfigProvider {
    override fun get(jobId: JobId): JobConfig = when (jobId) {
        JobId.FX_INGESTION -> JobConfig(jobId, "lock:fx", Duration.ofSeconds(35), Duration.ofSeconds(30))

        JobId.PREMIUM_REALTIME -> JobConfig(jobId, "lock:premium", Duration.ofSeconds(5), Duration.ofSeconds(3))

        JobId.PREMIUM_SUMMARY -> JobConfig(jobId, "lock:aggregation:summary", Duration.ofSeconds(30), Duration.ofSeconds(20))

        JobId.PREMIUM_AGGREGATION_MINUTE -> JobConfig(
            jobId,
            "lock:aggregation:minute",
            Duration.ofSeconds(30),
            Duration.ofSeconds(20),
        )

        JobId.PREMIUM_AGGREGATION_HOUR -> JobConfig(
            jobId,
            "lock:aggregation:hour",
            Duration.ofSeconds(60),
            Duration.ofSeconds(45),
        )

        JobId.PREMIUM_AGGREGATION_DAY -> JobConfig(jobId, "lock:aggregation:day", Duration.ofSeconds(120), Duration.ofSeconds(90))

        JobId.TICKER_AGGREGATION_MINUTE -> JobConfig(
            jobId,
            "lock:ticker:aggregation:minute",
            Duration.ofSeconds(30),
            Duration.ofSeconds(20),
        )

        JobId.TICKER_AGGREGATION_HOUR -> JobConfig(
            jobId,
            "lock:ticker:aggregation:hour",
            Duration.ofSeconds(60),
            Duration.ofSeconds(45),
        )

        JobId.TICKER_AGGREGATION_DAY -> JobConfig(
            jobId,
            "lock:ticker:aggregation:day",
            Duration.ofSeconds(120),
            Duration.ofSeconds(90),
        )

        JobId.BINANCE_TICKER_FLUSH -> JobConfig(jobId, "lock:ticker:flush:binance", Duration.ofSeconds(5), Duration.ofSeconds(3))

        JobId.BITHUMB_TICKER_FLUSH -> JobConfig(jobId, "lock:ticker:flush:bithumb", Duration.ofSeconds(5), Duration.ofSeconds(3))
    }
}
