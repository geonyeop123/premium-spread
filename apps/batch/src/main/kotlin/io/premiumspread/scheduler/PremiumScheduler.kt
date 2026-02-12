package io.premiumspread.scheduler

import io.premiumspread.application.common.JobConfig
import io.premiumspread.application.common.JobExecutor
import io.premiumspread.application.job.premium.PremiumRealtimeJob
import io.premiumspread.redis.RedisKeyGenerator
import io.premiumspread.redis.RedisTtl
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit

@Component
class PremiumScheduler(
    private val premiumRealtimeJob: PremiumRealtimeJob,
    private val jobExecutor: JobExecutor,
) {
    companion object {
        private val JOB_CONFIG = JobConfig(
            jobName = "premium",
            lockKey = RedisKeyGenerator.lockPremiumKey(),
            leaseTime = RedisTtl.Lock.PREMIUM_LEASE.seconds,
            leaseTimeUnit = TimeUnit.SECONDS,
        )
    }

    @Scheduled(fixedRate = 1000)
    fun calculatePremium() {
        jobExecutor.execute(JOB_CONFIG) { premiumRealtimeJob.run() }
    }
}
