package io.premiumspread.application.job.ownerops

import io.premiumspread.application.common.DefaultJobConfigProvider
import io.premiumspread.application.common.JobConfigProvider
import io.premiumspread.application.common.JobExecutor
import io.premiumspread.application.common.JobResult
import io.premiumspread.domain.accountpnl.OwnerAlertOutbox
import io.premiumspread.domain.accountpnl.OwnerAlertRows
import io.premiumspread.domain.accountpnl.OwnerAlertSignalState
import io.premiumspread.domain.batch.BatchMarket
import io.premiumspread.domain.batch.BatchMarketProvider
import io.premiumspread.domain.job.JobFailureStreakReadPort
import io.premiumspread.domain.job.JobId
import io.premiumspread.domain.job.JobLastSuccessReadPort
import io.premiumspread.domain.market.LatestMarketTickReadPort
import io.premiumspread.domain.owneralert.OwnerAlertRouting
import io.premiumspread.domain.ownerops.OpsAlertSettings
import io.premiumspread.domain.ownerops.OpsIncidentDecision
import io.premiumspread.domain.ownerops.OpsIncidentPolicy
import io.premiumspread.domain.ownerops.OpsNotice
import io.premiumspread.domain.ownerops.OpsObservation
import io.premiumspread.domain.ownerops.OpsSignal
import io.premiumspread.domain.ownerops.OpsSignals
import io.premiumspread.domain.ownerops.OwnerOpsAlertContent
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicReference

/** owner 운영 신호 1회차 판정과 enqueue. 발송은 OwnerAlertDeliveryJob 이 한다 (owner-ops-alerts design §8). */
@Suppress("LongParameterList")
class OwnerOpsSignalJob(
    private val lastSuccess: JobLastSuccessReadPort,
    private val failureStreaks: JobFailureStreakReadPort,
    private val ticks: LatestMarketTickReadPort,
    private val market: BatchMarketProvider,
    private val outbox: OwnerAlertOutbox,
    private val routing: OwnerAlertRouting,
    private val executor: JobExecutor,
    private val clock: Clock,
    private val settings: OpsAlertSettings = OpsAlertSettings(),
    private val zone: ZoneId = ZoneId.of("Asia/Seoul"),
    private val jobConfigs: JobConfigProvider = DefaultJobConfigProvider,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 이 인스턴스의 첫 실행 시각 — 재시작 직후 근거 없음(Unknown) 대기의 기준 (design §5.2 K4). */
    private val firstRunAt = AtomicReference<Instant?>(null)

    fun run(): JobResult = executor.execute(jobConfigs.get(JobId.OWNER_OPS_SIGNAL)) { evaluate() }

    @Suppress("ReturnCount")
    private fun evaluate(): JobResult {
        val now = clock.instant()
        firstRunAt.compareAndSet(null, now)
        val startedAt = checkNotNull(firstRunAt.get())

        val signals = try {
            signalsOf(market.defaultMarket())
        } catch (exception: Exception) {
            return storeFailure("defaultMarket", exception)
        }
        val loaded = try {
            outbox.loadSignals(signals.map { it.key })
        } catch (exception: Exception) {
            return storeFailure("loadSignals", exception)
        }

        // 관측을 모두 끝낸 뒤 판정한다 — 관측 중 취소가 다시 던져지면 아무것도 쓰지 않는다
        val observations = signals.map { observe(it, startedAt, now) }
        val decisions: List<OpsIncidentDecision> = signals.zip(observations) { signal, observation ->
            val current = loaded[signal.key] ?: OwnerAlertSignalState.initial(signal.key, signal.alertKind)
            OpsIncidentPolicy.decide(signal, current, observation, now, settings)
        }
        val notices = decisions.mapNotNull { it.notice }
        val rows = notices.flatMap { notice ->
            OwnerAlertRows.expand(OwnerOpsAlertContent.mail(notice, settings, zone), routing.resolve(notice.kind)) {
                OwnerOpsAlertContent.discord(notice, settings, zone)
            }
        }
        val changed = decisions.mapNotNull { it.next }
        if (changed.isEmpty()) return JobResult.Success

        if (Thread.currentThread().isInterrupted) {
            log.warn("Owner ops signal was cancelled before saving state")
            return JobResult.Skipped(CANCELLED)
        }

        val saved = try {
            outbox.enqueue(changed, rows)
        } catch (exception: Exception) {
            return storeFailure("enqueue", exception)
        }
        if (!saved) {
            log.info("Skipped owner ops signal: signal state version changed by another run")
            return JobResult.Skipped(VERSION_CONFLICT)
        }
        notices.forEach(::logDecided)
        return JobResult.Success
    }

    /** design §5.1 순서: FX → 시세(korea, foreign) → Job(MONITORED_JOBS 순서). */
    private fun signalsOf(market: BatchMarket): List<OpsSignal> = buildList {
        add(OpsSignal.FxIngestion(market.fxBase, market.fxQuote))
        add(OpsSignal.MarketStream(market.pair.koreaExchange, market.koreaQuote))
        add(OpsSignal.MarketStream(market.pair.foreignExchange, market.foreignQuote))
        MONITORED_JOBS.forEach { add(OpsSignal.JobFailures(it)) }
    }

    private fun observe(signal: OpsSignal, startedAt: Instant, now: Instant): OpsObservation = try {
        when (signal) {
            is OpsSignal.FxIngestion -> OpsSignals.fx(lastSuccess.findLastSuccess(JobId.FX_INGESTION), startedAt, now)
            is OpsSignal.MarketStream ->
                OpsSignals.market(ticks.findLatest(signal.exchange, signal.quote), startedAt, now, settings)
            is OpsSignal.JobFailures -> OpsSignals.job(
                failureStreaks.findFailureStreak(signal.jobId),
                lastSuccess.findLastSuccess(signal.jobId),
                now,
                settings,
            )
        }
    } catch (exception: Exception) {
        if (exception.isCancellation()) {
            // 취소를 Unknown 으로 삼키면 취소된 회차가 저장까지 진행한다 (adapter 가 감싼 경우 포함)
            Thread.currentThread().interrupt()
            throw exception
        }
        // 예외 메시지에는 주소·식별자가 섞일 수 있어 클래스 이름만 남긴다
        log.warn("Owner ops signal observation failed: signal={}, error={}", signal.key, exception.javaClass.simpleName)
        OpsObservation.Unknown
    }

    private fun logDecided(notice: OpsNotice) {
        val (kind, sequence) = when (notice) {
            is OpsNotice.Alert -> "alert" to notice.sequence
            is OpsNotice.Recovered -> "recovered" to notice.notifyCount
        }
        log.info(
            "Owner ops alert decided: signal={}, notice={}, sequence={}, eventKey={}",
            notice.signal.key,
            kind,
            sequence,
            OwnerOpsAlertContent.eventKey(notice),
        )
    }

    private fun storeFailure(operation: String, exception: Exception): JobResult {
        log.error("Owner ops signal store failed during {}", operation, exception)
        return JobResult.Failure(exception)
    }

    companion object {
        /** 자기 자신과 전달 Job 은 감시하지 않는다 (design §6.3). */
        val MONITORED_JOBS: List<JobId> =
            JobId.entries.filterNot { it == JobId.OWNER_OPS_SIGNAL || it == JobId.OWNER_ALERT_DELIVERY }
        const val CANCELLED = "cancelled"
        const val VERSION_CONFLICT = "state-version-conflict"
    }
}

private fun Throwable.isCancellation(): Boolean =
    generateSequence(this) { current -> current.cause?.takeUnless { it === current } }
        .take(MAX_CAUSE_DEPTH)
        .any { it is InterruptedException || it is CancellationException }

private const val MAX_CAUSE_DEPTH = 16
