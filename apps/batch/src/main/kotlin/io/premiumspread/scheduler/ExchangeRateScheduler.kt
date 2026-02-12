package io.premiumspread.scheduler

import io.premiumspread.application.common.JobConfig
import io.premiumspread.application.common.JobExecutor
import io.premiumspread.application.job.fx.FxIngestionJob
import io.premiumspread.redis.RedisKeyGenerator
import io.premiumspread.redis.RedisTtl
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit

@Component
class ExchangeRateScheduler(
    private val fxIngestionJob: FxIngestionJob,
    private val jobExecutor: JobExecutor,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private val JOB_CONFIG = JobConfig(
            jobName = "fx",
            lockKey = RedisKeyGenerator.lockFxKey(),
            leaseTime = RedisTtl.Lock.FX_LEASE.seconds,
            leaseTimeUnit = TimeUnit.SECONDS,
        )
    }

    @Scheduled(fixedRate = 1_800_000)
    fun fetchExchangeRate() {
        jobExecutor.execute(JOB_CONFIG) { fxIngestionJob.run() }
    }

    @Scheduled(initialDelay = 5000, fixedDelay = Long.MAX_VALUE)
    fun fetchExchangeRateOnStartup() {
        log.info("Fetching initial exchange rate...")
        fetchExchangeRate()
    }
}
