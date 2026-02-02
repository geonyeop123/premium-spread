package io.premiumspread.redis

import org.redisson.api.RLock
import org.redisson.api.RedissonClient
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit

/**
 * 분산 락 매니저
 *
 * Redisson을 사용한 분산 락 관리
 */
@Component
@ConditionalOnProperty(name = ["redis.enabled"], havingValue = "true", matchIfMissing = true)
class DistributedLockManager(
    private val redissonClient: RedissonClient,
) {

    private val logger = LoggerFactory.getLogger(DistributedLockManager::class.java)

    /**
     * 락을 획득하고 작업 실행
     *
     * @param lockKey 락 키
     * @param waitTime 락 획득 대기 시간 (0이면 즉시 반환)
     * @param leaseTime 락 유지 시간
     * @param timeUnit 시간 단위
     * @param action 락 획득 후 실행할 작업
     * @return 락 획득 및 작업 실행 성공 여부
     */
    fun <T> withLock(
        lockKey: String,
        waitTime: Long = 0,
        leaseTime: Long = 10,
        timeUnit: TimeUnit = TimeUnit.SECONDS,
        action: () -> T,
    ): LockResult<T> {
        val lock = redissonClient.getLock(lockKey)

        return try {
            val acquired = lock.tryLock(waitTime, leaseTime, timeUnit)
            if (acquired) {
                try {
                    val result = action()
                    LockResult.Success(result)
                } finally {
                    unlockSafely(lock, lockKey)
                }
            } else {
                logger.debug("Failed to acquire lock: {}", lockKey)
                LockResult.NotAcquired()
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            logger.warn("Lock acquisition interrupted: {}", lockKey, e)
            LockResult.Error(e)
        } catch (e: Exception) {
            logger.error("Error during locked operation: {}", lockKey, e)
            LockResult.Error(e)
        }
    }

    /**
     * 락 획득 시도 (작업 없이)
     *
     * @return 락 획득 성공 시 LockHandle 반환, 실패 시 null
     */
    fun tryAcquire(
        lockKey: String,
        waitTime: Long = 0,
        leaseTime: Long = 10,
        timeUnit: TimeUnit = TimeUnit.SECONDS,
    ): LockHandle? {
        val lock = redissonClient.getLock(lockKey)

        return try {
            val acquired = lock.tryLock(waitTime, leaseTime, timeUnit)
            if (acquired) {
                LockHandle(lock, lockKey)
            } else {
                null
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            logger.warn("Lock acquisition interrupted: {}", lockKey, e)
            null
        }
    }

    private fun unlockSafely(lock: RLock, lockKey: String) {
        try {
            if (lock.isHeldByCurrentThread) {
                lock.unlock()
            }
        } catch (e: Exception) {
            logger.warn("Failed to unlock: {}", lockKey, e)
        }
    }

    /**
     * 락 핸들 (수동 해제용)
     */
    inner class LockHandle(
        private val lock: RLock,
        private val lockKey: String,
    ) : AutoCloseable {

        fun release() {
            unlockSafely(lock, lockKey)
        }

        override fun close() {
            release()
        }
    }

    /**
     * 락 작업 결과
     */
    sealed class LockResult<out T> {
        data class Success<T>(val value: T) : LockResult<T>()
        class NotAcquired<T> : LockResult<T>()
        data class Error<T>(val exception: Exception) : LockResult<T>()

        fun isSuccess(): Boolean = this is Success
        fun isNotAcquired(): Boolean = this is NotAcquired
        fun isError(): Boolean = this is Error

        fun getOrNull(): T? = when (this) {
            is Success -> value
            else -> null
        }

        fun <R> map(transform: (T) -> R): LockResult<R> = when (this) {
            is Success -> Success(transform(value))
            is NotAcquired -> NotAcquired()
            is Error -> Error(exception)
        }
    }
}
