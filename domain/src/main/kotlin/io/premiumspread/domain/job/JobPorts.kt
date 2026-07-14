package io.premiumspread.domain.job

import java.time.Duration
import java.time.Instant

interface JobLock {
    fun tryAcquire(key: String, owner: String, lease: Duration, acquiredAt: Instant): Boolean

    fun release(key: String, owner: String)
}

interface JobRunRecorder {
    fun started(jobName: String, runId: String, at: Instant)

    fun succeeded(jobName: String, runId: String, at: Instant)

    fun failed(jobName: String, runId: String, at: Instant, reason: String)
}

enum class AlertSeverity {
    INFO,
    WARNING,
    CRITICAL,
}

data class OperatorAlertMessage(
    val code: String,
    val message: String,
    val severity: AlertSeverity,
    val occurredAt: Instant,
    val attributes: Map<String, String> = emptyMap(),
)

fun interface OperatorAlert {
    fun send(alert: OperatorAlertMessage)
}
