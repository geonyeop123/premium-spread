package io.premiumspread.application.job.accountpnl

import io.premiumspread.application.common.DefaultJobConfigProvider
import io.premiumspread.application.common.JobConfigProvider
import io.premiumspread.application.common.JobExecutor
import io.premiumspread.application.common.JobResult
import io.premiumspread.domain.accountpnl.AccountHedgePosition
import io.premiumspread.domain.accountpnl.AccountNetPnl
import io.premiumspread.domain.accountpnl.AccountPnlAlertStore
import io.premiumspread.domain.accountpnl.AccountPnlMail
import io.premiumspread.domain.accountpnl.AccountPnlMailContent
import io.premiumspread.domain.accountpnl.AccountPnlMailSender
import io.premiumspread.domain.accountpnl.AccountPnlMonitorState
import io.premiumspread.domain.accountpnl.AccountReadException
import io.premiumspread.domain.accountpnl.AccountReadFailureCategory
import io.premiumspread.domain.accountpnl.ForeignPerpAccountReadPort
import io.premiumspread.domain.accountpnl.ForeignPerpAccountSnapshot
import io.premiumspread.domain.accountpnl.KoreaSpotAccountReadPort
import io.premiumspread.domain.accountpnl.KoreaSpotAccountSnapshot
import io.premiumspread.domain.accountpnl.MonitoringOutagePolicy
import io.premiumspread.domain.accountpnl.OutageNotice
import io.premiumspread.domain.accountpnl.PnlAlertPolicy
import io.premiumspread.domain.accountpnl.PnlObservation
import io.premiumspread.domain.exchangerate.ExchangeRateSnapshot
import io.premiumspread.domain.job.JobId
import io.premiumspread.domain.job.JobLastSuccessReadPort
import io.premiumspread.domain.market.FxRateReadPort
import io.premiumspread.domain.ticker.Currency
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicReference

/**
 * 실계정 순손익 모니터링 1회차 (design §3·§4, dod AC5).
 *
 * 순서: stale claim 복구 → 상태 load → 관측(조회 → 신선도 → 포지션 판정 → 순손익 계산) → 수익·중단 정책
 * → 메일 생성 → 상태+메일 버전 조건부 저장 → claim → 발송·전이.
 *
 * - 관측 단계의 모든 예외는 실패 범주로 정규화해 중단 정책에 넣고 Job 은 성공으로 끝낸다
 *   (매분 CRITICAL 운영 알림을 막는다). 분류되지 않은 예외는 [AccountReadFailureCategory.UNEXPECTED].
 * - 상태 저장소 예외만 [JobResult.Failure] 다 — 중단 메일도 저장할 수 없으므로 운영 알림 경로에 맡긴다.
 * - 재발송 타이머(`lastSentAt`)는 발송 결정과 함께 저장되므로 SMTP 성공·실패가 바꾸지 않는다.
 */
@Suppress("LongParameterList", "TooManyFunctions")
class AccountPnlMonitorJob(
    private val korea: KoreaSpotAccountReadPort,
    private val foreign: ForeignPerpAccountReadPort,
    private val fx: FxRateReadPort,
    private val lastSuccess: JobLastSuccessReadPort,
    private val store: AccountPnlAlertStore,
    private val mailSender: AccountPnlMailSender,
    private val executor: JobExecutor,
    private val clock: Clock,
    private val zone: ZoneId = ZoneId.of("Asia/Seoul"),
    private val jobConfigs: JobConfigProvider = DefaultJobConfigProvider,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val owner = "account-pnl-monitor:" + UUID.randomUUID()

    /** FX 수집 성공 기록이 없을 때(프로세스 시작 직후) 2시간을 재는 기준. 이 인스턴스의 첫 실행 시각이다. */
    private val firstRunAt = AtomicReference<Instant?>(null)

    fun run(): JobResult = executor.execute(jobConfigs.get(JobId.ACCOUNT_PNL_MONITOR)) { monitor() }

    private fun monitor(): JobResult {
        val now = clock.instant()
        firstRunAt.compareAndSet(null, now)

        val loaded = try {
            val recovered = store.recoverStale(now, STALE_CLAIM_AFTER)
            if (recovered > 0) log.warn("Recovered {} stale account PnL mail claims", recovered)
            store.load()
        } catch (exception: Exception) {
            return storeFailure("recoverStale/load", exception)
        }

        val observed = observe(now)
        val alert = PnlAlertPolicy.decide(loaded.alert, observed.observation, now)
        val outage = MonitoringOutagePolicy.decide(loaded.outage, observed.failure, now)
        val mails = buildMails(observed.observation, alert.send, outage.notice, now)
        val next = AccountPnlMonitorState(alert.next, outage.next, loaded.version)

        // JobExecutor 가 timeout·lock 상실로 취소했으면 이 회차의 관측을 쓰지 않는다
        if (Thread.currentThread().isInterrupted) {
            log.warn("Account PnL monitor was cancelled before saving state")
            return JobResult.Skipped(CANCELLED)
        }

        val saved = try {
            store.save(loaded.version, next, mails)
        } catch (exception: Exception) {
            return storeFailure("save", exception)
        }
        if (!saved) {
            log.info("Skipped account PnL monitor: state version {} was changed by another run", loaded.version)
            return JobResult.Skipped(VERSION_CONFLICT)
        }

        return deliver()
    }

    // ---- 관측 ----

    private data class Observed(val observation: PnlObservation, val failure: AccountReadFailureCategory?)

    private fun observe(now: Instant): Observed = try {
        val koreaSnapshot = korea.read()
        val foreignSnapshot = foreign.read()
        checkMarketFreshness(koreaSnapshot, foreignSnapshot, now)
        val fxSnapshot = checkFxFreshness(now)

        val position = AccountHedgePosition.resolve(koreaSnapshot, foreignSnapshot)
        if (position == null) {
            log.info("No account hedge position: {}", noPositionReason(koreaSnapshot, foreignSnapshot))
            Observed(PnlObservation.NoPosition, null)
        } else {
            val pnl = AccountNetPnl.calculate(position, fxSnapshot.rate, fxSnapshot.observedAt, now)
            Observed(PnlObservation.Computed(pnl), null)
        }
    } catch (exception: Exception) {
        if (exception.isCancellation()) {
            // 취소를 실패 범주로 삼키면 취소된 회차가 저장·발송까지 진행한다 (adapter 가 감싼 경우 포함)
            Thread.currentThread().interrupt()
            throw exception
        }
        val category = (exception as? AccountReadException)?.category ?: AccountReadFailureCategory.UNEXPECTED
        log.warn("Account PnL observation failed: category={}", category, exception)
        Observed(PnlObservation.Failed, category)
    }

    private fun checkMarketFreshness(
        koreaSnapshot: KoreaSpotAccountSnapshot,
        foreignSnapshot: ForeignPerpAccountSnapshot,
        now: Instant,
    ) {
        val koreaAge = ageOf(koreaSnapshot.bidObservedAt, now)
        val foreignAge = ageOf(foreignSnapshot.askObservedAt, now)
        if (koreaAge > MAX_QUOTE_AGE || foreignAge > MAX_QUOTE_AGE) {
            throw AccountReadException(
                AccountReadFailureCategory.STALE_MARKET_DATA,
                "quote is older than ${MAX_QUOTE_AGE.toMillis()}ms (bithumb=${koreaAge.toMillis()}ms, " +
                    "binance=${foreignAge.toMillis()}ms)",
            )
        }
        val skew = Duration.between(koreaSnapshot.bidObservedAt, foreignSnapshot.askObservedAt).abs()
        if (skew > MAX_QUOTE_SKEW) {
            throw AccountReadException(
                AccountReadFailureCategory.STALE_MARKET_DATA,
                "quote observation skew ${skew.toMillis()}ms exceeds ${MAX_QUOTE_SKEW.toMillis()}ms",
            )
        }
    }

    /** 값의 나이가 아니라 수집이 마지막으로 성공한 시각으로 판정한다 (주말엔 원천 값이 바뀌지 않는다). */
    private fun checkFxFreshness(now: Instant): ExchangeRateSnapshot {
        val reference = lastSuccess.findLastSuccess(JobId.FX_INGESTION) ?: checkNotNull(firstRunAt.get())
        if (Duration.between(reference, now) > MAX_FX_INGESTION_GAP) {
            throw AccountReadException(
                AccountReadFailureCategory.STALE_FX,
                "fx ingestion has not succeeded since $reference",
            )
        }
        return fx.findLatest(Currency.USD, Currency.KRW)
            ?: throw AccountReadException(AccountReadFailureCategory.STALE_FX, "no USD/KRW rate is available")
    }

    /** 미래 시각(로컬 시계가 거래소보다 늦음)은 나이 0 으로 본다. */
    private fun ageOf(observedAt: Instant, now: Instant): Duration =
        Duration.between(observedAt, now).takeUnless { it.isNegative } ?: Duration.ZERO

    private fun noPositionReason(koreaSnapshot: KoreaSpotAccountSnapshot, foreignSnapshot: ForeignPerpAccountSnapshot) =
        when {
            koreaSnapshot.quantity < AccountHedgePosition.KOREA_DUST -> "korea-below-dust"
            foreignSnapshot.signedQuantity.signum() == 0 -> "foreign-flat"
            else -> "foreign-long"
        }

    // ---- 메일 ----

    private fun buildMails(
        observation: PnlObservation,
        sendProfit: Boolean,
        notice: OutageNotice?,
        now: Instant,
    ): List<AccountPnlMail> = buildList {
        if (sendProfit && observation is PnlObservation.Computed) add(AccountPnlMailContent.profit(observation.pnl, zone))
        when (notice) {
            is OutageNotice.Outage -> add(AccountPnlMailContent.outage(notice, now, zone))
            OutageNotice.Recovered -> add(AccountPnlMailContent.recovered(now, zone))
            null -> Unit
        }
    }

    private fun deliver(): JobResult {
        val claimed = try {
            store.claim(owner, CLAIM_LIMIT, clock.instant())
        } catch (exception: Exception) {
            return storeFailure("claim", exception)
        }
        for (mail in claimed) {
            val sent = try {
                mailSender.send(mail.mail)
                true
            } catch (exception: Exception) {
                // SMTP 예외 메시지에는 수신 주소가 실릴 수 있어 타입만 남긴다
                log.warn(
                    "Account PnL mail send failed: id={}, attempt={}, error={}",
                    mail.id,
                    mail.attemptCount + 1,
                    exception.javaClass.simpleName,
                )
                false
            }
            val applied = try {
                if (sent) {
                    store.markSent(mail.id, mail.claimToken, clock.instant())
                } else {
                    store.markAttemptFailed(mail.id, mail.claimToken, clock.instant())
                }
            } catch (exception: Exception) {
                return storeFailure(if (sent) "markSent" else "markAttemptFailed", exception)
            }
            if (!applied) log.warn("Account PnL mail {} transition was fenced (claim no longer owned)", mail.id)
        }
        return JobResult.Success
    }

    private fun storeFailure(operation: String, exception: Exception): JobResult {
        log.error("Account PnL alert store failed during {}", operation, exception)
        return JobResult.Failure(exception)
    }

    private companion object {
        val MAX_QUOTE_AGE: Duration = Duration.ofSeconds(10)
        val MAX_QUOTE_SKEW: Duration = Duration.ofSeconds(5)
        val MAX_FX_INGESTION_GAP: Duration = Duration.ofHours(2)
        val STALE_CLAIM_AFTER: Duration = Duration.ofMinutes(5)
        const val CLAIM_LIMIT = 10
        const val VERSION_CONFLICT = "state-version-conflict"
        const val CANCELLED = "cancelled"
    }
}

private fun Throwable.isCancellation(): Boolean =
    generateSequence(this) { current -> current.cause?.takeUnless { it === current } }
        .take(MAX_CAUSE_DEPTH)
        .any { it is InterruptedException || it is CancellationException }

private const val MAX_CAUSE_DEPTH = 16
