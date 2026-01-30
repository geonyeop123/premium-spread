package io.premiumspread.monitoring

import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/**
 * 배치 작업 헬스 인디케이터
 *
 * 배치 서버에서 사용하여 스케줄러 상태를 모니터링
 */
class BatchHealthIndicator(
    private val jobName: String,
    private val staleThreshold: Duration,
) : HealthIndicator {

    private val lastRunTime = AtomicReference<Instant?>(null)
    private val lastError = AtomicReference<String?>(null)
    private val consecutiveFailures = java.util.concurrent.atomic.AtomicInteger(0)

    override fun health(): Health {
        val lastRun = lastRunTime.get()
        val error = lastError.get()
        val failures = consecutiveFailures.get()

        if (lastRun == null) {
            return Health.unknown()
                .withDetail("job", jobName)
                .withDetail("message", "Job has not run yet")
                .build()
        }

        val elapsed = Duration.between(lastRun, Instant.now())
        val isStale = elapsed > staleThreshold

        val builder = if (isStale || failures >= MAX_CONSECUTIVE_FAILURES) {
            Health.down()
        } else if (error != null) {
            Health.outOfService()
        } else {
            Health.up()
        }

        return builder
            .withDetail("job", jobName)
            .withDetail("lastRun", lastRun.toString())
            .withDetail("elapsedSeconds", elapsed.seconds)
            .withDetail("staleThresholdSeconds", staleThreshold.seconds)
            .withDetail("isStale", isStale)
            .withDetail("consecutiveFailures", failures)
            .apply {
                if (error != null) {
                    withDetail("lastError", error)
                }
            }
            .build()
    }

    /**
     * 성공 기록
     */
    fun recordSuccess() {
        lastRunTime.set(Instant.now())
        lastError.set(null)
        consecutiveFailures.set(0)
    }

    /**
     * 실패 기록
     */
    fun recordFailure(errorMessage: String) {
        lastRunTime.set(Instant.now())
        lastError.set(errorMessage)
        consecutiveFailures.incrementAndGet()
    }

    /**
     * 마지막 실행 시간 조회
     */
    fun getLastRunTime(): Instant? = lastRunTime.get()

    companion object {
        private const val MAX_CONSECUTIVE_FAILURES = 5
    }
}
