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
    TRADE_PREPARATION_EVALUATION("trade_preparation_evaluation"),
    TRADE_PREPARATION_RECONCILE("trade_preparation_reconcile"),
    ACCOUNT_PNL_MONITOR("account_pnl_monitor"),
    OWNER_ALERT_DELIVERY("owner_alert_delivery"),
    OWNER_OPS_SIGNAL("owner_ops_signal"),
}

/**
 * 배치 작업이 마지막으로 성공한 시각. 기록이 없으면(프로세스 시작 후 성공한 적 없음 등) null.
 * 값의 나이가 아니라 "수집이 계속 성공하고 있는가"를 판정하는 용도다.
 */
fun interface JobLastSuccessReadPort {
    fun findLastSuccess(jobId: JobId): Instant?
}

/** 한 Job 의 현재 연속 실패. Success 에서 사라지고 Skipped 는 바꾸지 않는다. */
data class JobFailureStreak(
    val jobId: JobId,
    val consecutiveFailures: Int,
    val firstFailureAt: Instant,
    val lastFailureAt: Instant,
) {
    init {
        require(consecutiveFailures >= 1) { "consecutiveFailures must be at least 1." }
        require(!lastFailureAt.isBefore(firstFailureAt)) { "lastFailureAt must not be before firstFailureAt." }
    }
}

/** 이 프로세스가 기록한 연속 실패. 없으면 null(실패 없음 또는 재시작 후 아직 실패 없음). */
fun interface JobFailureStreakReadPort {
    fun findFailureStreak(jobId: JobId): JobFailureStreak?
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
