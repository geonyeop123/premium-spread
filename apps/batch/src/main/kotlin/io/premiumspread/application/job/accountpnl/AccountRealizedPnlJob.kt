package io.premiumspread.application.job.accountpnl

import io.premiumspread.application.common.DefaultJobConfigProvider
import io.premiumspread.application.common.JobConfigProvider
import io.premiumspread.application.common.JobExecutor
import io.premiumspread.application.common.JobResult
import io.premiumspread.domain.accountpnl.AccountRealizedPnlStore
import io.premiumspread.domain.accountpnl.AppliedFx
import io.premiumspread.domain.accountpnl.FillHistoryWindowLimitException
import io.premiumspread.domain.accountpnl.ForeignPerpFillReadPort
import io.premiumspread.domain.accountpnl.ForeignPerpPositionSource
import io.premiumspread.domain.accountpnl.ForeignRealized
import io.premiumspread.domain.accountpnl.KoreaRealized
import io.premiumspread.domain.accountpnl.KoreaSpotFillReadPort
import io.premiumspread.domain.accountpnl.RealizedPnlPolicy
import io.premiumspread.domain.accountpnl.SettlementFailure
import io.premiumspread.domain.accountpnl.SettlementOutcome
import io.premiumspread.domain.accountpnl.SettlementStatus
import io.premiumspread.domain.accountpnl.SettlementTarget
import io.premiumspread.domain.exchangerate.FxIngestionFreshness
import io.premiumspread.domain.job.JobId
import io.premiumspread.domain.job.JobLastSuccessReadPort
import io.premiumspread.domain.market.FxRateReadPort
import io.premiumspread.domain.ticker.Currency
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicReference

/**
 * 정리한 포지션의 확정 손익 정산 1회차 (#130 design D1·D5·D6, dod AC4).
 *
 * 순서: 대상 1건 선정 → 빗썸 체결 → 바이낸스 체결·펀딩(수동이면 MANUAL) → 두 쪽 CONFIRMED 면 환율 → 조건부 기록.
 * - 조회 범위는 `(lowerExclusive, occurredAt]` 이다. 매 회차 두 쪽을 처음부터 다시 계산한다.
 * - 조회·수량·환율 실패는 그 쪽(환율은 바이낸스 쪽)을 PENDING 으로 두고 사유 하나(빗썸 → 바이낸스 → FX 순 첫 실패)를 남긴다.
 *   확정된 쪽 값은 그대로 저장한다. Job 결과는 Success 다 — 재시도는 store 의 backoff(`nextAttemptAt`)가 맡는다.
 * - store 예외만 [JobResult.Failure] 다.
 * - 취소(`InterruptedException`·`CancellationException`, cause 체인 포함)는 interrupt 를 되살리고 다시 던진다.
 * - 로그에는 건 id·seq·상태·사유·예외 클래스명만 남긴다. 금액·수량·환율은 남기지 않는다.
 */
@Suppress("LongParameterList")
class AccountRealizedPnlJob(
    private val korea: KoreaSpotFillReadPort,
    /** 수동 모드면 null 이다. 이때 바이낸스 쪽은 항상 MANUAL 이다. */
    private val foreign: ForeignPerpFillReadPort?,
    private val store: AccountRealizedPnlStore,
    private val fx: FxRateReadPort,
    private val lastSuccess: JobLastSuccessReadPort,
    private val executor: JobExecutor,
    private val clock: Clock,
    private val settings: Settings = Settings(),
    private val jobConfigs: JobConfigProvider = DefaultJobConfigProvider,
) {
    /** `account-pnl-monitor.realized-pnl.*` 값. */
    data class Settings(
        val settleDelay: Duration = Duration.ofMinutes(2),
        val firstEpisodeLookback: Duration = Duration.ofHours(1),
        val maxAge: Duration = Duration.ofDays(90),
    )

    private val log = LoggerFactory.getLogger(javaClass)

    /** FX 수집 성공 기록이 없을 때 허용 공백을 재는 기준. 이 인스턴스의 첫 실행 시각이다 (모니터와 같은 규칙). */
    private val firstRunAt = AtomicReference<Instant?>(null)

    fun run(): JobResult = executor.execute(jobConfigs.get(JobId.ACCOUNT_REALIZED_PNL)) { settle() }

    private fun settle(): JobResult = try {
        val now = clock.instant()
        firstRunAt.compareAndSet(null, now)
        val target = storeCall("findTarget") {
            store.findTarget(now, settings.settleDelay, settings.maxAge, settings.firstEpisodeLookback)
        }
        if (target == null) JobResult.Success else record(decide(target, now), now)
    } catch (failure: StoreFailure) {
        log.error("Account realized PnL store failed during {}", failure.operation, failure.cause)
        JobResult.Failure(failure.cause)
    }

    private fun decide(target: SettlementTarget, now: Instant): SettlementOutcome {
        val koreaSide = readKorea(target)
        val foreignSide = readForeign(target)
        val bothConfirmed = koreaSide.status == SettlementStatus.CONFIRMED && foreignSide.status == SettlementStatus.CONFIRMED
        val fxSide = if (bothConfirmed) resolveFx(target, now) else FxSide(null, null)
        // 환율이 없으면 바이낸스 USDT 를 원화로 확정할 수 없다 — 바이낸스 값은 남기되 그 쪽을 PENDING 으로 둔다
        val foreignStatus = if (bothConfirmed && fxSide.fx == null) SettlementStatus.PENDING else foreignSide.status
        val total = fxSide.fx?.let { RealizedPnlPolicy.totalKrw(koreaSide.value!!, foreignSide.value!!, it.rate) }
        return SettlementOutcome(
            target = target,
            koreaStatus = koreaSide.status,
            korea = koreaSide.value,
            foreignStatus = foreignStatus,
            foreign = foreignSide.value,
            fx = fxSide.fx,
            totalKrw = total,
            failure = koreaSide.failure ?: foreignSide.failure ?: fxSide.failure,
            decidedAt = now,
        )
    }

    private fun readKorea(target: SettlementTarget): Side<KoreaRealized> = read("korea", KOREA_FAILURES) {
        val fills = korea.read(target.lowerExclusive, target.occurredAt)
        RealizedPnlPolicy.korea(fills, target.legs.korea.quantity)
    }

    private fun readForeign(target: SettlementTarget): Side<ForeignRealized> {
        val port = foreign
        if (port == null || target.source == ForeignPerpPositionSource.MANUAL_ENV) {
            return Side(SettlementStatus.MANUAL, null, null)
        }
        return read("foreign", FOREIGN_FAILURES) {
            val fills = port.fills(target.lowerExclusive, target.occurredAt)
            val funding = port.funding(target.lowerExclusive, target.occurredAt)
            RealizedPnlPolicy.foreign(fills, funding, target.legs.foreign.signedQuantity)
        }
    }

    /** null 결과는 수량 불일치, [FillHistoryWindowLimitException] 은 한도 초과, 그 밖의 예외는 조회 실패다. */
    private fun <T : Any> read(side: String, failures: SideFailures, compute: () -> T?): Side<T> = try {
        compute()?.let { Side(SettlementStatus.CONFIRMED, it, null) }
            ?: Side(SettlementStatus.PENDING, null, failures.quantity)
    } catch (exception: Exception) {
        rethrowIfCancelled(exception)
        val failure = if (exception is FillHistoryWindowLimitException) SettlementFailure.WINDOW_LIMIT else failures.read
        log.warn("Account realized PnL {} read failed: failure={}, error={}", side, failure, exception.javaClass.simpleName)
        Side(SettlementStatus.PENDING, null, failure)
    }

    /** 사건 시각(없으면 이전 10분 안) 관측 행의 환율, 없으면 FX 최신 값(모니터와 같은 수집 신선도 판정). */
    private fun resolveFx(target: SettlementTarget, now: Instant): FxSide {
        val atEvent = storeCall("findFxAt") { store.findFxAt(target.occurredAt, EVENT_FX_TOLERANCE) }
        if (atEvent != null) return FxSide(atEvent, null)
        val latest = try {
            latestFx(now)
        } catch (exception: Exception) {
            rethrowIfCancelled(exception)
            log.warn("Account realized PnL fx read failed: error={}", exception.javaClass.simpleName)
            null
        }
        return if (latest == null) FxSide(null, SettlementFailure.FX) else FxSide(latest, null)
    }

    private fun latestFx(now: Instant): AppliedFx? {
        val reference = lastSuccess.findLastSuccess(JobId.FX_INGESTION) ?: checkNotNull(firstRunAt.get())
        if (FxIngestionFreshness.isStale(reference, now)) {
            log.warn("Account realized PnL fx is stale: lastIngestionSuccess={}", reference)
            return null
        }
        return fx.findLatest(Currency.USD, Currency.KRW)?.let { AppliedFx(it.rate, it.observedAt) }
    }

    private fun record(outcome: SettlementOutcome, now: Instant): JobResult {
        // JobExecutor 가 timeout·lock 상실로 취소했으면 이 회차의 조회 결과를 쓰지 않는다
        if (Thread.currentThread().isInterrupted) {
            log.warn("Account realized PnL settlement was cancelled before recording")
            return JobResult.Skipped(CANCELLED)
        }
        val nextAttemptAt = if (outcome.settled) null else nextAttemptAt(now, outcome.target.attempts)
        val recorded = storeCall("record") { store.record(outcome, nextAttemptAt) }
        val target = outcome.target
        log.info(
            "Account realized PnL settlement: episodeId={}, seq={}, korea={}, foreign={}, failure={}, settled={}, " +
                "recorded={}, attempts={}",
            target.episodeId,
            target.seq,
            outcome.koreaStatus,
            outcome.foreignStatus,
            outcome.failure,
            outcome.settled,
            recorded,
            if (outcome.settled) 0 else target.attempts + 1,
        )
        return JobResult.Success
    }

    private fun <T> storeCall(operation: String, call: () -> T): T = try {
        call()
    } catch (exception: Exception) {
        rethrowIfCancelled(exception)
        throw StoreFailure(operation, exception)
    }

    private fun rethrowIfCancelled(exception: Exception) {
        if (exception.isCancellation()) {
            Thread.currentThread().interrupt()
            throw exception
        }
    }

    private data class Side<T>(val status: SettlementStatus, val value: T?, val failure: SettlementFailure?)

    private data class FxSide(val fx: AppliedFx?, val failure: SettlementFailure?)

    private data class SideFailures(val read: SettlementFailure, val quantity: SettlementFailure)

    private class StoreFailure(val operation: String, override val cause: Exception) : RuntimeException(cause)

    internal companion object {
        val EVENT_FX_TOLERANCE: Duration = Duration.ofMinutes(10)
        val BASE_BACKOFF: Duration = Duration.ofMinutes(5)
        val MAX_BACKOFF: Duration = Duration.ofHours(6)

        /** 5분 × 2^7 = 640분이 상한 6시간을 넘으므로 지수는 7 에서 자른다(overflow 방지). */
        private const val MAX_BACKOFF_EXPONENT = 7
        private const val CANCELLED = "cancelled"
        private val KOREA_FAILURES = SideFailures(SettlementFailure.KOREA_READ, SettlementFailure.KOREA_QUANTITY)
        private val FOREIGN_FAILURES = SideFailures(SettlementFailure.FOREIGN_READ, SettlementFailure.FOREIGN_QUANTITY)

        /** `now + 5분 × 2^(시도−1)`, 상한 6시간. [attemptsBefore] 는 이번 시도 전 횟수(= 이번 시도 − 1)다. */
        fun nextAttemptAt(now: Instant, attemptsBefore: Int): Instant {
            val exponent = attemptsBefore.coerceIn(0, MAX_BACKOFF_EXPONENT)
            val delay = BASE_BACKOFF.multipliedBy(1L shl exponent)
            return now.plus(if (delay > MAX_BACKOFF) MAX_BACKOFF else delay)
        }
    }
}

private fun Throwable.isCancellation(): Boolean =
    generateSequence(this) { current -> current.cause?.takeUnless { it === current } }
        .take(MAX_CAUSE_DEPTH)
        .any { it is InterruptedException || it is CancellationException }

private const val MAX_CAUSE_DEPTH = 16
