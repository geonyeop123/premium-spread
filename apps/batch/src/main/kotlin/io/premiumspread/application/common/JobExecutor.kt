package io.premiumspread.application.common

import io.micrometer.core.instrument.MeterRegistry
import io.premiumspread.redis.DistributedLockManager
import io.premiumspread.redis.RedisKeyGenerator
import io.premiumspread.redis.RedisTtl
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component

@Component
class JobExecutor(
    private val lockManager: DistributedLockManager,
    private val redisTemplate: StringRedisTemplate,
    private val meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun execute(config: JobConfig, action: () -> JobResult): JobResult {
        val lockResult = lockManager.withLock(
            lockKey = config.lockKey,
            waitTime = 0,
            leaseTime = config.leaseTime,
            timeUnit = config.leaseTimeUnit,
            action = action,
        )

        return when (lockResult) {
            is DistributedLockManager.LockResult.Success -> {
                val jobResult = lockResult.value
                recordMetrics(config.jobName, jobResult)
                if (jobResult is JobResult.Success) {
                    updateLastRunTime(config.jobName)
                }
                jobResult
            }

            is DistributedLockManager.LockResult.NotAcquired -> {
                log.trace("{} skipped - lock not acquired", config.jobName)
                val skipped = JobResult.Skipped("lock")
                recordMetrics(config.jobName, skipped)
                skipped
            }

            is DistributedLockManager.LockResult.Error -> {
                log.error("{} failed with lock error", config.jobName, lockResult.exception)
                val failure = JobResult.Failure(lockResult.exception)
                recordMetrics(config.jobName, failure)
                failure
            }
        }
    }

    private fun recordMetrics(jobName: String, result: JobResult) {
        when (result) {
            is JobResult.Success ->
                meterRegistry.counter("scheduler.$jobName.success").increment()

            is JobResult.Skipped ->
                meterRegistry.counter("scheduler.$jobName.skipped", "reason", result.reason).increment()

            is JobResult.Failure ->
                meterRegistry.counter("scheduler.$jobName.error", "error", result.exception.javaClass.simpleName).increment()
        }
    }

    private fun updateLastRunTime(jobName: String) {
        redisTemplate.opsForValue().set(
            RedisKeyGenerator.batchLastRunKey(jobName),
            System.currentTimeMillis().toString(),
            RedisTtl.BATCH_HEALTH,
        )
    }
}
