package io.premiumspread.config

import io.premiumspread.application.common.JobConfig
import io.premiumspread.application.common.JobConfigProvider
import io.premiumspread.domain.job.JobId
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.time.Duration

@Validated
@ConfigurationProperties(prefix = "batch.jobs")
data class BatchJobProperties(
    @field:Valid val fxIngestion: LockSpec = LockSpec("lock:fx", Duration.ofSeconds(35), Duration.ofSeconds(30)),
    @field:Valid val premiumRealtime: LockSpec = LockSpec("lock:premium", Duration.ofSeconds(5), Duration.ofSeconds(3)),
    @field:Valid val premiumSummary: LockSpec =
    LockSpec("lock:aggregation:summary", Duration.ofSeconds(30), Duration.ofSeconds(20)),
    @field:Valid val premiumMinute: LockSpec =
    LockSpec("lock:aggregation:minute", Duration.ofSeconds(30), Duration.ofSeconds(20)),
    @field:Valid val premiumHour: LockSpec = LockSpec("lock:aggregation:hour", Duration.ofSeconds(60), Duration.ofSeconds(45)),
    @field:Valid val premiumDay: LockSpec = LockSpec("lock:aggregation:day", Duration.ofSeconds(120), Duration.ofSeconds(90)),
    @field:Valid val tickerMinute: LockSpec =
    LockSpec("lock:ticker:aggregation:minute", Duration.ofSeconds(30), Duration.ofSeconds(20)),
    @field:Valid val tickerHour: LockSpec =
    LockSpec("lock:ticker:aggregation:hour", Duration.ofSeconds(60), Duration.ofSeconds(45)),
    @field:Valid val tickerDay: LockSpec =
    LockSpec("lock:ticker:aggregation:day", Duration.ofSeconds(120), Duration.ofSeconds(90)),
    @field:Valid val binanceFlush: LockSpec = LockSpec("lock:ticker:flush:binance", Duration.ofSeconds(5), Duration.ofSeconds(3)),
    @field:Valid val bithumbFlush: LockSpec = LockSpec("lock:ticker:flush:bithumb", Duration.ofSeconds(5), Duration.ofSeconds(3)),
    @field:Valid val tradePreparationEvaluation: LockSpec =
    LockSpec("lock:trade-preparation:evaluation", Duration.ofSeconds(5), Duration.ofSeconds(3)),
    @field:Valid val tradePreparationReconcile: LockSpec =
    LockSpec("lock:trade-preparation:reconcile", Duration.ofSeconds(30), Duration.ofSeconds(20)),
) : JobConfigProvider {
    override fun get(jobId: JobId): JobConfig = when (jobId) {
        JobId.FX_INGESTION -> fxIngestion
        JobId.PREMIUM_REALTIME -> premiumRealtime
        JobId.PREMIUM_SUMMARY -> premiumSummary
        JobId.PREMIUM_AGGREGATION_MINUTE -> premiumMinute
        JobId.PREMIUM_AGGREGATION_HOUR -> premiumHour
        JobId.PREMIUM_AGGREGATION_DAY -> premiumDay
        JobId.TICKER_AGGREGATION_MINUTE -> tickerMinute
        JobId.TICKER_AGGREGATION_HOUR -> tickerHour
        JobId.TICKER_AGGREGATION_DAY -> tickerDay
        JobId.BINANCE_TICKER_FLUSH -> binanceFlush
        JobId.BITHUMB_TICKER_FLUSH -> bithumbFlush
        JobId.TRADE_PREPARATION_EVALUATION -> tradePreparationEvaluation
        JobId.TRADE_PREPARATION_RECONCILE -> tradePreparationReconcile
    }.toJobConfig(jobId)

    data class LockSpec(@field:NotBlank val lockKey: String, val lease: Duration, val executionTimeout: Duration) {
        init {
            require(lease.isPositive()) { "lease must be positive." }
            require(executionTimeout.isPositive()) { "executionTimeout must be positive." }
            require(lease > executionTimeout) { "lease must exceed executionTimeout." }
        }

        fun toJobConfig(jobId: JobId) = JobConfig(jobId, lockKey, lease, executionTimeout)
    }
}

private fun Duration.isPositive() = !isZero && !isNegative
