package io.premiumspread.application.common

import io.premiumspread.domain.job.JobLock
import io.premiumspread.support.BatchIntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DuplicateJobLockIntegrationTest : BatchIntegrationTestBase() {
    @Autowired
    lateinit var jobLock: JobLock

    @Test
    fun `two scheduler instances cannot hold the same distributed lock`() {
        val pool = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        val release = CountDownLatch(1)
        val attempts = (1..2).map { instance ->
            pool.submit<Boolean> {
                start.await()
                val owner = "instance-$instance"
                val acquired = jobLock.tryAcquire("lock:test:duplicate", owner, Duration.ofSeconds(5), Instant.now())
                if (acquired) {
                    release.await(2, TimeUnit.SECONDS)
                    jobLock.release("lock:test:duplicate", owner)
                }
                acquired
            }
        }

        start.countDown()
        Thread.sleep(200)
        release.countDown()
        val results = attempts.map { it.get(3, TimeUnit.SECONDS) }
        pool.shutdownNow()

        assertThat(results.count { it }).isEqualTo(1)
    }

    @Test
    fun `owner token만 lease를 갱신하고 lock을 해제할 수 있다`() {
        val key = "lock:test:owner-token"
        val lease = Duration.ofSeconds(5)
        val now = Instant.now()
        assertThat(jobLock.tryAcquire(key, "owner-a", lease, now)).isTrue()

        assertThat(jobLock.renew(key, "owner-b", lease)).isFalse()
        jobLock.release(key, "owner-b")
        assertThat(jobLock.tryAcquire(key, "owner-b", lease, now)).isFalse()

        Thread.sleep(200)
        val ttlBeforeRenew = redisTemplate.getExpire(key, TimeUnit.MILLISECONDS)
        assertThat(jobLock.renew(key, "owner-a", lease)).isTrue()
        val ttlAfterRenew = redisTemplate.getExpire(key, TimeUnit.MILLISECONDS)
        assertThat(ttlAfterRenew).isGreaterThan(ttlBeforeRenew)
        assertThat(jobLock.tryAcquire(key, "owner-b", lease, Instant.now())).isFalse()

        jobLock.release(key, "owner-a")
        assertThat(jobLock.tryAcquire(key, "owner-b", lease, Instant.now())).isTrue()
        jobLock.release(key, "owner-b")
    }
}
