package io.premiumspread.infrastructure.batch.job

import io.micrometer.core.instrument.MeterRegistry
import io.premiumspread.domain.job.JobLock
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import java.time.Duration
import java.time.Instant

class RedisJobLockAdapter(
    private val redisTemplate: StringRedisTemplate,
    private val meterRegistry: MeterRegistry,
) : JobLock {
    override fun tryAcquire(key: String, owner: String, lease: Duration, acquiredAt: Instant): Boolean {
        return try {
            val acquired = redisTemplate.opsForValue().setIfAbsent(key, owner, lease) == true
            record(if (acquired) "acquired" else "not_acquired")
            acquired
        } catch (exception: RuntimeException) {
            record("error")
            throw exception
        }
    }

    override fun renew(key: String, owner: String, lease: Duration): Boolean {
        return try {
            val renewed = redisTemplate.execute(RENEW_SCRIPT, listOf(key), owner, lease.toMillis().toString()) == 1L
            record(if (renewed) "renewed" else "ownership_lost")
            renewed
        } catch (exception: RuntimeException) {
            record("error")
            throw exception
        }
    }

    override fun release(key: String, owner: String) {
        try {
            redisTemplate.execute(RELEASE_SCRIPT, listOf(key), owner)
            record("released")
        } catch (exception: RuntimeException) {
            record("error")
            throw exception
        }
    }

    private fun record(outcome: String) {
        meterRegistry.counter(METRIC_NAME, "outcome", outcome).increment()
    }

    companion object {
        private const val METRIC_NAME = "batch.job.lock"
        private val RENEW_SCRIPT = DefaultRedisScript(
            """
            if redis.call('get', KEYS[1]) == ARGV[1] then
                return redis.call('pexpire', KEYS[1], ARGV[2])
            end
            return 0
            """.trimIndent(),
            Long::class.java,
        )
        private val RELEASE_SCRIPT = DefaultRedisScript(
            """
            if redis.call('get', KEYS[1]) == ARGV[1] then
                return redis.call('del', KEYS[1])
            end
            return 0
            """.trimIndent(),
            Long::class.java,
        )
    }
}
