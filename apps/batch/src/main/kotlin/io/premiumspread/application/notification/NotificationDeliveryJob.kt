package io.premiumspread.application.notification

import io.premiumspread.config.NotificationDeliveryProperties
import io.premiumspread.domain.notification.ClaimedNotificationDelivery
import io.premiumspread.domain.notification.NotificationSender
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.net.InetAddress
import java.time.Clock
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@Component
@ConditionalOnProperty(prefix = "notification.email", name = ["enabled"], havingValue = "true")
class NotificationDeliveryJob(
    private val transactions: NotificationDeliveryTransactionService,
    private val sender: NotificationSender,
    private val properties: NotificationDeliveryProperties,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val workerId = createWorkerId(hostName(), UUID.randomUUID().toString())
    private val threadSequence = AtomicInteger()
    private val capacity = Semaphore(properties.concurrency, true)
    private val executor: ExecutorService = Executors.newFixedThreadPool(properties.concurrency) { task ->
        Thread.ofPlatform()
            .name("notification-delivery-${threadSequence.incrementAndGet()}")
            .daemon(true)
            .unstarted(task)
    }
    private val deadlineScheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { task ->
        Thread.ofPlatform().name("notification-delivery-deadline").daemon(true).unstarted(task)
    }

    /**
     * 실행 capacity를 먼저 예약한 수만큼만 claim한다. deadline interrupt를 무시하는 SMTP 작업은
     * 실제 종료할 때까지 permit을 점유하므로 다음 poll이 실행 대기 row를 PROCESSING으로 고립시키지 않는다.
     */
    fun poll() {
        val now = clock.instant()
        transactions.recoverStale(now.minus(properties.staleThreshold), now)

        val reserved = reserveCapacity()
        if (reserved == 0) return

        val claimed = try {
            transactions.claimReady(now, minOf(properties.batchSize, reserved), workerId)
        } catch (error: Exception) {
            capacity.release(reserved)
            throw error
        }
        capacity.release(reserved - claimed.size)
        claimed.forEach(::submit)
    }

    private fun reserveCapacity(): Int {
        val desired = minOf(properties.batchSize, capacity.availablePermits())
        if (desired == 0 || !capacity.tryAcquire(desired)) return 0
        return desired
    }

    private fun submit(delivery: ClaimedNotificationDelivery) {
        try {
            executor.execute {
                runWithDeadline(delivery)
            }
        } catch (error: RejectedExecutionException) {
            capacity.release()
            returnToRetry(delivery, "DeliveryExecutorRejected")
        }
    }

    private fun runWithDeadline(delivery: ClaimedNotificationDelivery) {
        val executingThread = Thread.currentThread()
        val running = AtomicBoolean(true)
        var deadline: ScheduledFuture<*>? = null
        try {
            deadline = try {
                deadlineScheduler.schedule(
                    {
                        if (running.compareAndSet(true, false)) {
                            executingThread.interrupt()
                            log.warn(
                                "Notification send exceeded hard deadline; completion remains claim-token fenced - deliveryId={}",
                                delivery.deliveryId,
                            )
                        }
                    },
                    properties.hardSendDeadline.toMillis(),
                    TimeUnit.MILLISECONDS,
                )
            } catch (error: RejectedExecutionException) {
                returnToRetry(delivery, "DeliveryDeadlineSchedulerRejected")
                return
            }
            sendAndTransition(delivery)
        } finally {
            running.set(false)
            deadline?.cancel(false)
            // timeout interrupt가 다음 delivery task에 전파되지 않게 executor worker 상태를 정리한다.
            Thread.interrupted()
            capacity.release()
        }
    }

    private fun returnToRetry(delivery: ClaimedNotificationDelivery, reason: String) {
        val now = clock.instant()
        runCatching {
            transactions.scheduleRetry(delivery, now, reason, now)
        }.onFailure { transitionError ->
            log.error(
                "Notification task could not be returned to retry - deliveryId={}, errorType={}",
                delivery.deliveryId,
                transitionError.javaClass.simpleName,
            )
        }
    }

    internal fun sendAndTransition(delivery: ClaimedNotificationDelivery) {
        if (delivery.attemptCount > properties.maxAttempts) {
            transactions.markFailed(delivery, "MaxAttemptsExceeded", clock.instant())
            return
        }
        try {
            sender.deliver(delivery)
            transactions.markSent(delivery, clock.instant())
        } catch (error: Exception) {
            val now = clock.instant()
            val sanitizedReason = error.javaClass.simpleName.take(120).ifBlank { "DeliveryFailure" }
            if (delivery.attemptCount >= properties.maxAttempts) {
                transactions.markFailed(delivery, sanitizedReason, now)
            } else {
                transactions.scheduleRetry(delivery, now.plus(retryDelay(delivery.attemptCount)), sanitizedReason, now)
            }
        }
    }

    private fun retryDelay(attemptCount: Int): Duration {
        val base = properties.retryDelays[(attemptCount - 1).coerceIn(0, properties.retryDelays.lastIndex)]
        if (properties.retryJitterRatio == 0.0) return base
        val maximumJitterMillis = (base.toMillis() * properties.retryJitterRatio).toLong()
        if (maximumJitterMillis == 0L) return base
        return base.plusMillis(ThreadLocalRandom.current().nextLong(maximumJitterMillis + 1))
    }

    @PreDestroy
    fun close() {
        deadlineScheduler.shutdownNow()
        executor.shutdownNow()
    }

    private fun hostName(): String = runCatching { InetAddress.getLocalHost().hostName }
        .getOrDefault("batch")

    internal companion object {
        private const val MAX_LOCKED_BY_LENGTH = 100
        private const val CLAIM_UUID_LENGTH = 36
        private const val SEPARATOR_LENGTH = 1

        fun createWorkerId(hostName: String, uuid: String): String {
            require(uuid.length == CLAIM_UUID_LENGTH) { "worker UUID must use canonical 36-character form" }
            val hostBudget = MAX_LOCKED_BY_LENGTH - CLAIM_UUID_LENGTH - SEPARATOR_LENGTH
            return "${hostName.ifBlank { "batch" }.take(hostBudget)}-$uuid"
        }
    }
}
