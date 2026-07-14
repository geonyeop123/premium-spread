package io.premiumspread.infrastructure.batch.job

import io.premiumspread.domain.job.JobLock
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import java.time.Duration
import java.time.Instant

class RedisJobLockAdapter(
    private val redisTemplate: StringRedisTemplate,
) : JobLock {
    override fun tryAcquire(key: String, owner: String, lease: Duration, acquiredAt: Instant): Boolean {
        return redisTemplate.opsForValue().setIfAbsent(key, owner, lease) == true
    }

    override fun renew(key: String, owner: String, lease: Duration): Boolean {
        return redisTemplate.execute(RENEW_SCRIPT, listOf(key), owner, lease.toMillis().toString()) == 1L
    }

    override fun release(key: String, owner: String) {
        redisTemplate.execute(RELEASE_SCRIPT, listOf(key), owner)
    }

    companion object {
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
