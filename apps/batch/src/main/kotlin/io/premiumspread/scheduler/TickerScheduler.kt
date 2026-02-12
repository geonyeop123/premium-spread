package io.premiumspread.scheduler

import io.premiumspread.application.common.JobConfig
import io.premiumspread.application.common.JobExecutor
import io.premiumspread.application.job.ticker.TickerIngestionJob
import io.premiumspread.redis.RedisKeyGenerator
import io.premiumspread.redis.RedisTtl
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit

@Component
class TickerScheduler(
    private val tickerIngestionJob: TickerIngestionJob,
    private val jobExecutor: JobExecutor,
) {
    companion object {
        private val JOB_CONFIG = JobConfig(
            jobName = "ticker",
            lockKey = RedisKeyGenerator.lockTickerKey(),
            leaseTime = RedisTtl.Lock.TICKER_LEASE.seconds,
            leaseTimeUnit = TimeUnit.SECONDS,
        )
    }

    @Scheduled(fixedRate = 1000)
    fun fetchTickers() {
        jobExecutor.execute(JOB_CONFIG) { tickerIngestionJob.run() }
    }
}
