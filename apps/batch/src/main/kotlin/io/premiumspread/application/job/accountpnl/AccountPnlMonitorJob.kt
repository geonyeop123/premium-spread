package io.premiumspread.application.job.accountpnl

import io.premiumspread.application.common.DefaultJobConfigProvider
import io.premiumspread.application.common.JobConfigProvider
import io.premiumspread.application.common.JobExecutor
import io.premiumspread.application.common.JobResult
import io.premiumspread.application.notification.owneralert.OwnerAlertDispatcher
import io.premiumspread.domain.accountpnl.AccountAlert
import io.premiumspread.domain.accountpnl.AccountAlertContent
import io.premiumspread.domain.accountpnl.AccountAlertDecision
import io.premiumspread.domain.accountpnl.AccountAlertPolicy
import io.premiumspread.domain.accountpnl.AccountAlertSettings
import io.premiumspread.domain.accountpnl.AccountAlertState
import io.premiumspread.domain.accountpnl.AccountHedgePosition
import io.premiumspread.domain.accountpnl.AccountNetPnl
import io.premiumspread.domain.accountpnl.AccountPnlAlertStore
import io.premiumspread.domain.accountpnl.AccountPnlAlertContent
import io.premiumspread.domain.accountpnl.AccountPnlLatest
import io.premiumspread.domain.accountpnl.AccountPnlLatestStore
import io.premiumspread.domain.accountpnl.AccountPnlMail
import io.premiumspread.domain.accountpnl.AccountPnlMailSender
import io.premiumspread.domain.accountpnl.AccountPnlMonitorState
import io.premiumspread.domain.accountpnl.AccountReadException
import io.premiumspread.domain.accountpnl.AccountReadFailureCategory
import io.premiumspread.domain.accountpnl.AccountRound
import io.premiumspread.domain.accountpnl.ForeignPerpAccountReadPort
import io.premiumspread.domain.accountpnl.ForeignPerpAccountSnapshot
import io.premiumspread.domain.accountpnl.KoreaSpotAccountReadPort
import io.premiumspread.domain.accountpnl.KoreaSpotAccountSnapshot
import io.premiumspread.domain.accountpnl.MonitoringOutagePolicy
import io.premiumspread.domain.accountpnl.OutageNotice
import io.premiumspread.domain.accountpnl.OwnerAlertRows
import io.premiumspread.domain.accountpnl.PnlAlertPolicy
import io.premiumspread.domain.accountpnl.PnlObservation
import io.premiumspread.domain.accountpnl.PositionLegs
import io.premiumspread.domain.alertmessage.OwnerAlertMessage
import io.premiumspread.domain.exchangerate.ExchangeRateSnapshot
import io.premiumspread.domain.exchangerate.FxIngestionFreshness
import io.premiumspread.domain.job.JobId
import io.premiumspread.domain.job.JobLastSuccessReadPort
import io.premiumspread.domain.market.FxRateReadPort
import io.premiumspread.domain.owneralert.DiscordMessage
import io.premiumspread.domain.owneralert.OwnerAlertDiscordSender
import io.premiumspread.domain.owneralert.OwnerAlertKind
import io.premiumspread.domain.owneralert.OwnerAlertRouting
import io.premiumspread.domain.ticker.Currency
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.math.RoundingMode
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
 * → 알림 생성·종류별 채널 라우팅 → 상태+outbox 행 버전 조건부 저장 → 전달([OwnerAlertDispatcher]: claim → 채널별 발송·전이).
 *
 * - 관측 단계의 모든 예외는 실패 범주로 정규화해 중단 정책에 넣고 Job 은 성공으로 끝낸다
 *   (매분 CRITICAL 운영 알림을 막는다). 분류되지 않은 예외는 [AccountReadFailureCategory.UNEXPECTED].
 * - 상태 저장소 예외만 [JobResult.Failure] 다 — 중단 메일도 저장할 수 없으므로 운영 알림 경로에 맡긴다.
 * - 재발송 타이머(`lastSentAt`)는 발송 결정과 함께 저장되므로 SMTP 성공·실패가 바꾸지 않는다.
 * - 시각: 관측 판정(호가 나이·환율 수집 공백·`calculatedAt`)은 두 계정 조회를 마친 뒤의 `clock.instant()` 를,
 *   상태 전이(stale 복구·수익·중단 정책·메일 시각)는 회차 시작 시각 `now` 를 쓴다. claim·mark 는 그 순간의 시각이다.
 * - 예외: owner 계정 알림(포지션 변경·순수익률 급변·미반영 리마인더)은 관측 완료 시각(`completedAt` = 포지션이 있으면 `pnl.calculatedAt`)을 쓴다.
 *   급변 버킷이 정시 보고와 같은 버킷(`calculatedAt` 의 zone 정시)이어야 하기 때문이다 (owner-account-alerts design §5.1).
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
    /** 정시 보고 메일. 기본 꺼짐 — 운영 설정(`account-pnl-monitor.hourly-report.enabled`)이 켠다. */
    private val hourlyReportEnabled: Boolean = false,
    /** 알림 종류별 채널 경로. 기본은 메일만(기존 동작). */
    private val routing: OwnerAlertRouting = OwnerAlertRouting.EMAIL_ONLY,
    /** Discord 채널 sender. [routing] 이 Discord 채널을 쓰면 필수다. */
    private val discordSender: OwnerAlertDiscordSender? = null,
    /** owner 계정 알림 설정(#89). null 이면 판정하지 않고 account 상태를 그대로 저장한다. production 배선은 항상 넘긴다. */
    private val accountAlerts: AccountAlertSettings? = null,
    /** 실계정 최신 순수익률 기록(#90 일일 요약용). null 이면 기록하지 않는다. */
    private val latestPnl: AccountPnlLatestStore? = null,
) {
    init {
        require(!routing.channels.discord || discordSender != null) {
            "owner-alert discord channel is enabled but no Discord sender was provided"
        }
    }

    private val log = LoggerFactory.getLogger(javaClass)
    private val owner = "account-pnl-monitor:" + UUID.randomUUID()
    private val dispatcher = OwnerAlertDispatcher(store, mailSender, discordSender, clock, owner)

    /** FX 수집 성공 기록이 없을 때(프로세스 시작 직후) 허용 공백(`FxIngestionFreshness.MAX_INGESTION_GAP`)을 재는 기준. 이 인스턴스의 첫 실행 시각이다. */
    private val firstRunAt = AtomicReference<Instant?>(null)

    fun run(): JobResult = executor.execute(jobConfigs.get(JobId.ACCOUNT_PNL_MONITOR)) { monitor() }

    @Suppress("ReturnCount")
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

        val observed = observe()
        val alert = PnlAlertPolicy.decide(loaded.alert, observed.observation, now)
        val outage = MonitoringOutagePolicy.decide(loaded.outage, observed.failure, now)
        val mails = buildMails(observed.observation, alert.send, outage.notice, now)
        val account = accountAlerts?.let { AccountAlertPolicy.decide(loaded.account, observed.round, it, zone) }
            ?: AccountAlertDecision(loaded.account, emptyList())
        val rows = mails + account.alerts.flatMap { accountAlertRows(it) }
        val next = AccountPnlMonitorState(alert.next, outage.next, loaded.version, account.next)
        logAccountDecision(loaded.account, account)

        // JobExecutor 가 timeout·lock 상실로 취소했으면 이 회차의 관측을 쓰지 않는다
        if (Thread.currentThread().isInterrupted) {
            log.warn("Account PnL monitor was cancelled before saving state")
            return JobResult.Skipped(CANCELLED)
        }

        val saved = try {
            store.save(loaded.version, next, rows)
        } catch (exception: Exception) {
            return storeFailure("save", exception)
        }
        if (!saved) {
            log.info("Skipped account PnL monitor: state version {} was changed by another run", loaded.version)
            return JobResult.Skipped(VERSION_CONFLICT)
        }

        recordLatest(observed)

        return deliver()
    }

    /** 성공 회차(Computed·NoPosition)의 최신 순수익률을 기록한다. 실패해도 회차 결과·전달은 바뀌지 않는다 (design §6.3). */
    private fun recordLatest(observed: Observed) {
        val store = latestPnl ?: return
        val latest = when (val observation = observed.observation) {
            is PnlObservation.Computed -> AccountPnlLatest.computed(observation.pnl)
            PnlObservation.NoPosition -> observed.round?.observedAt?.let { AccountPnlLatest.noPosition(it) }
            PnlObservation.Failed -> null
        } ?: return
        try {
            store.record(latest)
        } catch (exception: Exception) {
            if (exception.isCancellation()) {
                Thread.currentThread().interrupt()
            } else {
                log.warn("Account PnL latest record failed: error={}", exception.javaClass.simpleName)
            }
        }
    }

    // ---- 관측 ----

    private data class Observed(
        val observation: PnlObservation,
        val failure: AccountReadFailureCategory?,
        /** 성공 회차(Computed·NoPosition)만. 실패면 null. */
        val round: AccountRound?,
    )

    private fun observe(): Observed = try {
        val koreaSnapshot = korea.read()
        val foreignSnapshot = foreign.read()
        // 조회(이력 탐색 포함)에 걸린 시간을 호가 나이에 넣는다 — 회차 시작 시각으로 재면 오래된 호가가 신선해 보인다
        val completedAt = clock.instant()
        checkMarketFreshness(koreaSnapshot, foreignSnapshot, completedAt)
        val fxSnapshot = checkFxFreshness(completedAt)

        val position = AccountHedgePosition.resolve(koreaSnapshot, foreignSnapshot)
        if (position == null) {
            log.info("No account hedge position: {}", noPositionReason(koreaSnapshot, foreignSnapshot))
            Observed(PnlObservation.NoPosition, null, round(koreaSnapshot, foreignSnapshot, null, completedAt))
        } else {
            val pnl = AccountNetPnl.calculate(position, fxSnapshot.rate, fxSnapshot.observedAt, completedAt)
            logObserved(pnl)
            Observed(PnlObservation.Computed(pnl), null, round(koreaSnapshot, foreignSnapshot, pnl, completedAt))
        }
    } catch (exception: Exception) {
        if (exception.isCancellation()) {
            // 취소를 실패 범주로 삼키면 취소된 회차가 저장·발송까지 진행한다 (adapter 가 감싼 경우 포함)
            Thread.currentThread().interrupt()
            throw exception
        }
        val category = (exception as? AccountReadException)?.category ?: AccountReadFailureCategory.UNEXPECTED
        log.warn("Account PnL observation failed: category={}", category, exception)
        Observed(PnlObservation.Failed, category, null)
    }

    private fun round(
        koreaSnapshot: KoreaSpotAccountSnapshot,
        foreignSnapshot: ForeignPerpAccountSnapshot,
        pnl: AccountNetPnl?,
        completedAt: Instant,
    ) = AccountRound(
        PositionLegs.of(koreaSnapshot, foreignSnapshot),
        foreignSnapshot.positionSource,
        foreignSnapshot.fundingUsdt,
        pnl,
        completedAt,
    )

    /** 회차마다 계산된 순손익 한 줄. 스냅샷에서 계산된 숫자만 남기고 credential·수신자·env 원문은 싣지 않는다. */
    private fun logObserved(pnl: AccountNetPnl) {
        log.info(
            "Account PnL observed: netPercent={}%, netKrw={}, netPercentAtLast={}%, netKrwAtLast={}, grossKrw={}, " +
                "entryFeeKrw={}, fundingKrw={}, exitFeeEstimateKrw={}, koreaQty={}, koreaExit={}, foreignQty={}, " +
                "foreignExit={}, fxRate={}, source={}, unrecovered={}, calculatedAt={}",
            pnl.netPercent.toPlainString(),
            pnl.netKrw.krw(),
            pnl.netPercentAtLast.toPlainString(),
            pnl.netKrwAtLast.krw(),
            pnl.grossKrw.krw(),
            pnl.entryFeeKrw.krw(),
            pnl.fundingKrw.krw(),
            pnl.exitFeeEstimateKrw.krw(),
            pnl.position.koreaQuantity.plain(),
            pnl.koreaExitPrice.plain(),
            pnl.position.foreignQuantity.plain(),
            pnl.foreignExitPrice.plain(),
            pnl.fxRate.plain(),
            pnl.position.foreign.positionSource,
            unrecoveredItems(pnl),
            pnl.calculatedAt,
        )
    }

    private fun unrecoveredItems(pnl: AccountNetPnl): List<String> = buildList {
        if (pnl.koreaEntryFeeUnrecovered) add("koreaEntryFee")
        if (pnl.foreignEntryFeeUnrecovered) add("foreignEntryFee")
        if (pnl.fundingUnrecovered) add("funding")
    }

    private fun BigDecimal.krw(): String = setScale(0, RoundingMode.HALF_UP).toPlainString()

    private fun BigDecimal.plain(): String = stripTrailingZeros().toPlainString()

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
        if (FxIngestionFreshness.isStale(reference, now)) {
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
        if (sendProfit && observation is PnlObservation.Computed) {
            val pnl = observation.pnl
            addAll(
                route(OwnerAlertKind.PROFIT, AccountPnlAlertContent.profit(pnl, zone)),
            )
        }
        // 같은 정시 버킷의 eventKey 는 outbox 가 무시하므로 회차마다 넣어도 채널마다 시간당 한 통이다
        if (hourlyReportEnabled && observation is PnlObservation.Computed) {
            val pnl = observation.pnl
            addAll(
                route(OwnerAlertKind.HOURLY_REPORT, AccountPnlAlertContent.hourly(pnl, zone)),
            )
        }
        when (notice) {
            is OutageNotice.Outage -> addAll(
                route(OwnerAlertKind.MONITORING_OUTAGE, AccountPnlAlertContent.outage(notice, now, zone)),
            )

            OutageNotice.Recovered -> addAll(
                route(OwnerAlertKind.MONITORING_RECOVERED, AccountPnlAlertContent.recovered(now, zone)),
            )

            null -> Unit
        }
    }

    /** 알림 하나를 종류별 경로로 0~2행(메일 먼저)으로 펼친다 (design §3.4). Discord 렌더링은 Discord 행을 만들 때만 한다. */
    private fun route(kind: OwnerAlertKind, mail: AccountPnlMail, discord: () -> DiscordMessage): List<AccountPnlMail> =
        OwnerAlertRows.expand(mail, routing.resolve(kind), discord)

    private fun route(kind: OwnerAlertKind, message: OwnerAlertMessage): List<AccountPnlMail> =
        OwnerAlertRows.expand(message, routing.resolve(kind))

    private fun accountAlertRows(alert: AccountAlert): List<AccountPnlMail> =
        route(alert.kind, AccountAlertContent.mail(alert, zone)) { AccountAlertContent.discord(alert, zone) }

    /** 행을 만든 뒤·저장 전에 남기는 "decided" 로그다 (발송 로그가 아니다). 수량·가격·펀딩·객체 toString 은 싣지 않는다. */
    private fun logAccountDecision(before: AccountAlertState, decision: AccountAlertDecision) {
        decision.alerts.forEach { alert ->
            log.info(
                "Owner account alert decided: kind={}, eventKey={}",
                alert.kind.name,
                AccountAlertContent.eventKey(alert),
            )
            if (alert is AccountAlert.PositionChanged) {
                log.info(
                    "Account position changed: korea={}, foreign={}, hedgeMismatchWarning={}",
                    alert.koreaChanged,
                    alert.foreignChanged,
                    alert.hedgeMismatchWarning,
                )
            }
        }
        if (before.position.legs == null && decision.next.position.legs != null) {
            log.info("Account position legs recorded; first observation sends no change alert")
        }
    }

    /** 전달은 [OwnerAlertDispatcher] 가 소유한다 (design §5.2). 같은 회차 안에서 즉시 전달한다. */
    private fun deliver(): JobResult = dispatcher.dispatch()

    private fun storeFailure(operation: String, exception: Exception): JobResult {
        log.error("Account PnL alert store failed during {}", operation, exception)
        return JobResult.Failure(exception)
    }

    private companion object {
        val MAX_QUOTE_AGE: Duration = Duration.ofSeconds(10)
        val MAX_QUOTE_SKEW: Duration = Duration.ofSeconds(5)
        val STALE_CLAIM_AFTER: Duration = Duration.ofMinutes(5)
        const val VERSION_CONFLICT = "state-version-conflict"
        const val CANCELLED = "cancelled"
    }
}

private fun Throwable.isCancellation(): Boolean =
    generateSequence(this) { current -> current.cause?.takeUnless { it === current } }
        .take(MAX_CAUSE_DEPTH)
        .any { it is InterruptedException || it is CancellationException }

private const val MAX_CAUSE_DEPTH = 16
