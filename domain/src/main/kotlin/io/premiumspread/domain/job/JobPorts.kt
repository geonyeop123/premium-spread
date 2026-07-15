package io.premiumspread.domain.job

import java.time.Duration
import java.time.Instant

interface JobLock {
    fun tryAcquire(key: String, owner: String, lease: Duration, acquiredAt: Instant): Boolean

    /** 현재 owner가 보유한 lock만 원자적으로 연장한다. 소유권을 잃었으면 false를 반환한다. */
    fun renew(key: String, owner: String, lease: Duration): Boolean

    fun release(key: String, owner: String)
}

/**
 * 운영 메트릭과 last-run 저장소에 기록할 수 있는 유한한 배치 작업 식별자다.
 * 외부 입력을 그대로 metric tag로 사용하지 않도록 모든 스케줄 작업을 이 enum에 등록한다.
 */
enum class JobId(val tag: String) {
    FX_INGESTION("fx_ingestion"),
    PREMIUM_REALTIME("premium_realtime"),
    PREMIUM_SUMMARY("premium_summary"),
    PREMIUM_AGGREGATION_MINUTE("premium_aggregation_minute"),
    PREMIUM_AGGREGATION_HOUR("premium_aggregation_hour"),
    PREMIUM_AGGREGATION_DAY("premium_aggregation_day"),
    TICKER_AGGREGATION_MINUTE("ticker_aggregation_minute"),
    TICKER_AGGREGATION_HOUR("ticker_aggregation_hour"),
    TICKER_AGGREGATION_DAY("ticker_aggregation_day"),
    BINANCE_TICKER_FLUSH("binance_ticker_flush"),
    BITHUMB_TICKER_FLUSH("bithumb_ticker_flush"),
}

enum class JobRunOutcome {
    SUCCEEDED,
    SKIPPED,
    FAILED,
}

interface JobRunRecorder {
    fun started(jobId: JobId, runId: String, at: Instant)

    /**
     * [detail]은 로그/진단용이며 metric tag로 사용하면 안 된다.
     * metric name은 `batch.job.run`, tag는 [JobId.tag]/[JobRunOutcome.name]처럼 유한한 값만 사용한다.
     */
    fun completed(
        jobId: JobId,
        runId: String,
        outcome: JobRunOutcome,
        at: Instant,
        detail: String? = null,
    )
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
