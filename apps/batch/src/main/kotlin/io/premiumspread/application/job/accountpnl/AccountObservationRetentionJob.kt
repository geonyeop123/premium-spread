package io.premiumspread.application.job.accountpnl

import io.premiumspread.application.common.DefaultJobConfigProvider
import io.premiumspread.application.common.JobConfigProvider
import io.premiumspread.application.common.JobExecutor
import io.premiumspread.application.common.JobResult
import io.premiumspread.domain.accountpnl.AccountObservationRetentionStore
import io.premiumspread.domain.job.JobId
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.temporal.ChronoUnit
import java.util.concurrent.CancellationException

/**
 * 보존 기간이 지난 매분 관측 이력 정리 (#148 design D4).
 *
 * - cutoff = `now − retention` 을 마이크로초로 자른다(DB 는 DATETIME(6)).
 * - 배치(트랜잭션 1개)를 최대 [Settings.maxBatchesPerRun] 번 반복하고, 배치가 [Settings.batchSize] 보다 적게 지우면 멈춘다.
 *   상한에 닿으면 WARN 하고 다음 회차에 이어 간다.
 * - 배치 사이에 interrupt 를 확인한다(timeout·lock 상실 취소). 취소 예외는 interrupt 를 되살리고 다시 던진다.
 * - store 예외만 [JobResult.Failure] 다. 로그에는 건수와 예외 클래스명만 남긴다.
 */
class AccountObservationRetentionJob(
    private val store: AccountObservationRetentionStore,
    private val executor: JobExecutor,
    private val clock: Clock,
    private val settings: Settings,
    private val jobConfigs: JobConfigProvider = DefaultJobConfigProvider,
) {
    /** `account-pnl-monitor.position-history.retention.*` 값. */
    data class Settings(val retention: Duration, val batchSize: Int, val maxBatchesPerRun: Int)

    private val log = LoggerFactory.getLogger(javaClass)

    fun run(): JobResult = executor.execute(jobConfigs.get(JobId.ACCOUNT_OBSERVATION_RETENTION)) { purge() }

    private fun purge(): JobResult {
        val cutoff = clock.instant().minus(settings.retention).truncatedTo(ChronoUnit.MICROS)
        var deleted = 0
        var batches = 0
        while (batches < settings.maxBatchesPerRun) {
            if (Thread.currentThread().isInterrupted) {
                log.warn("Account observation retention cancelled - deleted={}, batches={}", deleted, batches)
                return JobResult.Skipped(CANCELLED)
            }
            val count = try {
                store.deleteObservedBefore(cutoff, settings.batchSize)
            } catch (exception: Exception) {
                if (exception.isCancellation()) {
                    Thread.currentThread().interrupt()
                    throw exception
                }
                log.error("Account observation retention failed - error={}", exception.javaClass.simpleName)
                return JobResult.Failure(exception)
            }
            batches++
            deleted += count
            if (count < settings.batchSize) {
                log.info("Account observation retention done - deleted={}, batches={}", deleted, batches)
                return JobResult.Success
            }
        }
        log.warn(
            "Account observation retention reached per-run safety cap - deleted={}, maxBatches={}, batchSize={}",
            deleted,
            settings.maxBatchesPerRun,
            settings.batchSize,
        )
        return JobResult.Success
    }

    private fun Throwable.isCancellation(): Boolean = generateSequence(this) { current ->
        current.cause?.takeUnless { it === current }
    }.take(MAX_CAUSE_DEPTH).any { it is InterruptedException || it is CancellationException }

    private companion object {
        const val CANCELLED = "cancelled"
        const val MAX_CAUSE_DEPTH = 16
    }
}
