package io.premiumspread.application.common

import io.premiumspread.domain.job.AlertSeverity
import io.premiumspread.domain.job.JobLock
import io.premiumspread.domain.job.JobRunOutcome
import io.premiumspread.domain.job.JobRunRecorder
import io.premiumspread.domain.job.OperatorAlert
import io.premiumspread.domain.job.OperatorAlertMessage
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationListener
import org.springframework.context.event.ContextClosedEvent
import org.springframework.core.Ordered
import org.springframework.stereotype.Component
import java.time.Clock
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

@Component
class JobExecutor(
    private val jobLock: JobLock,
    private val runRecorder: JobRunRecorder,
    private val operatorAlert: OperatorAlert,
    private val clock: Clock,
) : ApplicationListener<ContextClosedEvent>,
    Ordered {
    private val log = LoggerFactory.getLogger(javaClass)
    private val shuttingDown = AtomicBoolean(false)
    private val taskExecutor = Executors.newVirtualThreadPerTaskExecutor()
    private val leaseExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "batch-job-lock-renewal").apply { isDaemon = true }
    }

    /**
     * 자식 컨텍스트(management 등) 종료 이벤트는 부모 리스너로 전파되므로 루트 컨텍스트 종료만 받는다.
     * `ApplicationContextAware` 로 자기 컨텍스트를 받지 않는 이유: 테스트가 이 타입의 strict mock 을 빈으로 등록한다.
     */
    private fun isOwnContextEvent(event: ContextClosedEvent): Boolean = event.applicationContext.parent == null

    /**
     * Context 종료 신호는 lifecycle stop·bean destroy 보다 먼저 온다.
     * 이후 종료 과정에서 생긴 실패는 장애가 아니라 종료로 분류한다.
     */
    override fun onApplicationEvent(event: ContextClosedEvent) {
        if (!isOwnContextEvent(event)) return
        shuttingDown.set(true)
    }

    override fun getOrder(): Int = Ordered.HIGHEST_PRECEDENCE

    fun execute(config: JobConfig, action: () -> JobResult): JobResult {
        if (shuttingDown.get()) {
            log.debug("{} skipped - application shutting down", config.jobId.tag)
            return JobResult.Skipped(SHUTDOWN).also { recordCompletion(config, UUID.randomUUID().toString(), it) }
        }
        val runId = UUID.randomUUID().toString()
        val acquiredAt = clock.instant()
        val acquired = try {
            jobLock.tryAcquire(config.lockKey, runId, config.lease, acquiredAt)
        } catch (exception: Exception) {
            val result = shutdownAware(config, JobResult.Failure(exception))
            if (result is JobResult.Failure) log.error("{} failed while acquiring lock", config.jobId.tag, exception)
            recordCompletion(config, runId, result)
            if (result is JobResult.Failure) alertAfterRelease(config, result)
            return result
        }

        if (!acquired) {
            log.trace("{} skipped - lock not acquired", config.jobId.tag)
            return JobResult.Skipped("lock").also { recordCompletion(config, runId, it) }
        }

        runCatching { runRecorder.started(config.jobId, runId, clock.instant()) }
            .onFailure { log.warn("Failed to record {} start", config.jobId.tag, it) }

        val submitted = submit(action)
        val renewal = startLeaseRenewal(config, runId, submitted)
        val awaited = awaitResult(config, submitted)
        if (awaited.actionMayStillRun) {
            // Timeout 응답은 즉시 반환하되, 실제 action 종료 전에는 lock을 절대 해제하지 않는다.
            // 취소를 무시하는 JDBC/Redis I/O가 남아 있어도 renewal이 중복 실행을 막는다.
            // 종료가 영원히 지연되더라도 timeout 자체는 즉시 운영 신호로 남긴다.
            val result = shutdownAware(config, awaited.result)
            recordCompletion(config, runId, result)
            if (result is JobResult.Failure) alertWhileLockRetained(config, result)
            taskExecutor.submit {
                submitted.completion.await()
                releaseAfterDeferredAction(config, runId, renewal)
            }
            return result
        }

        return completeAfterAction(config, runId, awaited.result, renewal)
    }

    private fun submit(action: () -> JobResult): SubmittedAction {
        val started = AtomicBoolean(false)
        val completion = CountDownLatch(1)
        val future = taskExecutor.submit<JobResult> {
            started.compareAndSet(false, true)
            try {
                action()
            } finally {
                completion.countDown()
            }
        }
        return SubmittedAction(future, started, completion)
    }

    private fun awaitResult(config: JobConfig, submitted: SubmittedAction): AwaitedResult =
        try {
            AwaitedResult(
                submitted.future.get(config.executionTimeout.toMillis(), TimeUnit.MILLISECONDS),
                actionMayStillRun = false,
            )
        } catch (expectedTimeout: TimeoutException) {
            cancel(submitted)
            AwaitedResult(
                JobResult.Failure(JobExecutionTimeoutException(config.jobId.tag, config.executionTimeout)),
                actionMayStillRun = submitted.completion.count > 0,
            )
        } catch (exception: ExecutionException) {
            AwaitedResult(JobResult.Failure(exception.cause?.asException() ?: exception), actionMayStillRun = false)
        } catch (expectedCancellation: CancellationException) {
            AwaitedResult(
                JobResult.Failure(JobLockOwnershipLostException(config.jobId.tag)),
                actionMayStillRun = submitted.completion.count > 0,
            )
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            cancel(submitted)
            AwaitedResult(JobResult.Failure(exception), actionMayStillRun = submitted.completion.count > 0)
        }

    private fun cancel(submitted: SubmittedAction) {
        submitted.future.cancel(true)
        // Future가 실행 전에 취소되면 callable의 finally가 실행되지 않는다.
        if (submitted.started.compareAndSet(false, true)) {
            submitted.completion.countDown()
        }
    }

    private fun startLeaseRenewal(
        config: JobConfig,
        runId: String,
        submitted: SubmittedAction,
    ): ScheduledFuture<*> {
        val intervalMs = (config.lease.toMillis() / 3).coerceAtLeast(1L)
        return leaseExecutor.scheduleAtFixedRate(
            {
                if (submitted.completion.count == 0L) return@scheduleAtFixedRate
                runCatching { jobLock.renew(config.lockKey, runId, config.lease) }
                    .onSuccess { renewed ->
                        if (!renewed) {
                            log.error("{} lost distributed lock ownership while action is running", config.jobId.tag)
                            submitted.future.cancel(true)
                        }
                    }
                    .onFailure {
                        log.error("{} failed to renew distributed lock", config.jobId.tag, it)
                        submitted.future.cancel(true)
                    }
            },
            intervalMs,
            intervalMs,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun completeAfterAction(
        config: JobConfig,
        runId: String,
        initialResult: JobResult,
        renewal: ScheduledFuture<*>,
    ): JobResult {
        renewal.cancel(false)
        var result = initialResult
        try {
            jobLock.release(config.lockKey, runId)
        } catch (exception: Exception) {
            log.error("{} failed while releasing lock", config.jobId.tag, exception)
            result = JobResult.Failure(exception)
        }
        result = shutdownAware(config, result)
        recordCompletion(config, runId, result)
        if (result is JobResult.Failure) alertAfterRelease(config, result)
        return result
    }

    private fun releaseAfterDeferredAction(
        config: JobConfig,
        runId: String,
        renewal: ScheduledFuture<*>,
    ) {
        renewal.cancel(false)
        runCatching { jobLock.release(config.lockKey, runId) }
            .onFailure { log.error("{} failed while releasing retained timeout lock", config.jobId.tag, it) }
    }

    @PreDestroy
    fun close() {
        taskExecutor.shutdownNow()
        leaseExecutor.shutdownNow()
    }

    private fun shutdownAware(config: JobConfig, result: JobResult): JobResult {
        if (!shuttingDown.get() || result !is JobResult.Failure) return result
        log.warn("{} interrupted by application shutdown - recorded as skipped", config.jobId.tag, result.exception)
        return JobResult.Skipped(SHUTDOWN)
    }

    private fun recordCompletion(config: JobConfig, runId: String, result: JobResult) {
        val outcome = when (result) {
            JobResult.Success -> JobRunOutcome.SUCCEEDED
            is JobResult.Skipped -> JobRunOutcome.SKIPPED
            is JobResult.Failure -> JobRunOutcome.FAILED
        }
        val detail = when (result) {
            JobResult.Success -> null
            is JobResult.Skipped -> result.reason
            is JobResult.Failure -> result.exception.javaClass.simpleName
        }
        runCatching { runRecorder.completed(config.jobId, runId, outcome, clock.instant(), detail) }
            .onFailure { log.warn("Failed to record {} completion", config.jobId.tag, it) }
    }

    private fun alertAfterRelease(config: JobConfig, failure: JobResult.Failure) {
        enqueueFailureAlert(config, failure, lockRetained = false)
    }

    private fun alertWhileLockRetained(config: JobConfig, failure: JobResult.Failure) {
        enqueueFailureAlert(config, failure, lockRetained = true)
    }

    private fun enqueueFailureAlert(
        config: JobConfig,
        failure: JobResult.Failure,
        lockRetained: Boolean,
    ) {
        runCatching {
            operatorAlert.send(
                OperatorAlertMessage(
                    code = "BATCH_JOB_FAILED",
                    message = "[${config.jobId.tag}] 작업 실패: ${failure.exception.message}",
                    severity = AlertSeverity.CRITICAL,
                    occurredAt = clock.instant(),
                    attributes = mapOf(
                        "job" to config.jobId.tag,
                        "error" to failure.exception.javaClass.simpleName,
                        "lock_retained" to lockRetained.toString(),
                    ),
                ),
            )
        }.onFailure { log.error("Failed to enqueue {} operator alert", config.jobId.tag, it) }
    }

    companion object {
        const val SHUTDOWN = "shutdown"
    }
}

private data class SubmittedAction(val future: Future<JobResult>, val started: AtomicBoolean, val completion: CountDownLatch)

private data class AwaitedResult(val result: JobResult, val actionMayStillRun: Boolean)

class JobExecutionTimeoutException(job: String, timeout: java.time.Duration) :
    RuntimeException("$job exceeded execution timeout ${timeout.toMillis()}ms")

class JobLockOwnershipLostException(job: String) : RuntimeException("$job lost distributed lock ownership while executing")

private fun Throwable.asException(): Exception = this as? Exception ?: RuntimeException(this)
