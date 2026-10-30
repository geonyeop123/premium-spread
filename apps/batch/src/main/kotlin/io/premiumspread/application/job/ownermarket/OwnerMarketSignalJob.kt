package io.premiumspread.application.job.ownermarket

import io.premiumspread.application.common.DefaultJobConfigProvider
import io.premiumspread.application.common.JobConfigProvider
import io.premiumspread.application.common.JobExecutor
import io.premiumspread.application.common.JobResult
import io.premiumspread.domain.accountpnl.AccountPnlLatestStore
import io.premiumspread.domain.accountpnl.OwnerAlertRows
import io.premiumspread.domain.aggregation.AggregationUnit
import io.premiumspread.domain.aggregation.PremiumAggregationReadPort
import io.premiumspread.domain.batch.BatchMarket
import io.premiumspread.domain.batch.BatchMarketProvider
import io.premiumspread.domain.job.JobId
import io.premiumspread.domain.market.FxRateReadPort
import io.premiumspread.domain.market.MarketPair
import io.premiumspread.domain.owneralert.OwnerAlertKind
import io.premiumspread.domain.owneralert.OwnerAlertRouting
import io.premiumspread.domain.ownermarket.DailySummaryInput
import io.premiumspread.domain.ownermarket.DailySummaryPolicy
import io.premiumspread.domain.ownermarket.ExtremeStates
import io.premiumspread.domain.ownermarket.ExtremeWindow
import io.premiumspread.domain.ownermarket.MarketAlertSettings
import io.premiumspread.domain.ownermarket.MarketAlertState
import io.premiumspread.domain.ownermarket.MarketDecision
import io.premiumspread.domain.ownermarket.MarketDirection
import io.premiumspread.domain.ownermarket.MarketEvidence
import io.premiumspread.domain.ownermarket.MarketKeys
import io.premiumspread.domain.ownermarket.MarketObservation
import io.premiumspread.domain.ownermarket.MarketWindows
import io.premiumspread.domain.ownermarket.OwnerMarketAlertContent
import io.premiumspread.domain.ownermarket.OwnerMarketAlertStore
import io.premiumspread.domain.ownermarket.PremiumExtremePolicy
import io.premiumspread.domain.ownermarket.PremiumSwingPolicy
import io.premiumspread.domain.ownermarket.PremiumThresholdPolicy
import io.premiumspread.domain.premium.PremiumReadPort
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.ZoneId
import java.util.concurrent.CancellationException

/** owner 시장 신호 1회차 판정과 enqueue. 발송은 OwnerAlertDeliveryJob 이 한다 (owner-market-alerts design §7.4). */
@Suppress("LongParameterList")
class OwnerMarketSignalJob(
    private val premiums: PremiumReadPort,
    private val aggregations: PremiumAggregationReadPort,
    private val fx: FxRateReadPort,
    private val pnlLatest: AccountPnlLatestStore,
    private val market: BatchMarketProvider,
    private val store: OwnerMarketAlertStore,
    private val routing: OwnerAlertRouting,
    private val executor: JobExecutor,
    private val clock: Clock,
    private val settings: MarketAlertSettings = MarketAlertSettings(),
    private val zone: ZoneId = ZoneId.of("Asia/Seoul"),
    private val jobConfigs: JobConfigProvider = DefaultJobConfigProvider,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun run(): JobResult = executor.execute(jobConfigs.get(JobId.OWNER_MARKET_SIGNAL)) { evaluate() }

    @Suppress("ReturnCount", "LongMethod")
    private fun evaluate(): JobResult {
        val now = clock.instant()
        val batchMarket: BatchMarket
        val pair: MarketPair
        val pairKey: String
        try {
            batchMarket = market.defaultMarket()
            pair = batchMarket.pair
            pairKey = MarketKeys.pairKey(pair)
        } catch (exception: Exception) {
            return storeFailure("defaultMarket", exception)
        }

        val thresholdKey = MarketKeys.threshold(pairKey).takeIf { settings.thresholdBaseline != null }
        val keys = buildList {
            add(MarketKeys.swing(pairKey, MarketDirection.UP))
            add(MarketKeys.swing(pairKey, MarketDirection.DOWN))
            thresholdKey?.let(::add)
            ExtremeWindow.entries.forEach { window ->
                MarketDirection.entries.forEach { add(MarketKeys.extreme(pairKey, window, it)) }
            }
            add(MarketKeys.dailySummary(pairKey))
        }
        val loaded = try {
            store.load(keys)
        } catch (exception: Exception) {
            return storeFailure("load", exception)
        }
        fun state(key: String, kind: OwnerAlertKind) = loaded[key] ?: MarketAlertState.initial(key, kind)

        // 관측을 모두 끝낸 뒤 판정한다 - 관측 중 취소가 다시 던져지면 아무것도 쓰지 않는다
        val windows = MarketWindows.of(now, zone)
        val observation = MarketObservation(
            pair,
            observe("current") { MarketEvidence.current(premiums.findLatest(pair), pair, now) },
            observe("minutes") { aggregations.read(pair, windows.minutes, AggregationUnit.MINUTE) },
            observe("hours") { aggregations.read(pair, windows.hours, AggregationUnit.HOUR) },
        )

        val decisions = mutableListOf<MarketDecision>()
        decisions += PremiumSwingPolicy.decide(
            observation,
            state(MarketKeys.swing(pairKey, MarketDirection.UP), OwnerAlertKind.PREMIUM_SWING),
            state(MarketKeys.swing(pairKey, MarketDirection.DOWN), OwnerAlertKind.PREMIUM_SWING),
            settings,
            now,
        )
        thresholdKey?.let {
            decisions += PremiumThresholdPolicy.decide(
                observation,
                state(it, OwnerAlertKind.PREMIUM_THRESHOLD_CROSSED),
                settings,
                now,
            )
        }
        fun extreme(window: ExtremeWindow, direction: MarketDirection) =
            state(MarketKeys.extreme(pairKey, window, direction), OwnerAlertKind.PREMIUM_NEW_EXTREME)
        decisions += PremiumExtremePolicy.decide(
            observation,
            ExtremeStates(
                extreme(ExtremeWindow.ONE_HOUR, MarketDirection.UP),
                extreme(ExtremeWindow.ONE_HOUR, MarketDirection.DOWN),
                extreme(ExtremeWindow.ONE_DAY, MarketDirection.UP),
                extreme(ExtremeWindow.ONE_DAY, MarketDirection.DOWN),
            ),
            settings,
            now,
        )
        val summaryState = state(MarketKeys.dailySummary(pairKey), OwnerAlertKind.DAILY_SUMMARY)
        if (DailySummaryPolicy.due(summaryState, settings, now, zone)) {
            summaryInput(batchMarket, pair, windows)?.let {
                decisions += DailySummaryPolicy.decide(summaryState, pair, it, settings, now, zone)
            }
        }

        val notices = decisions.flatMap { it.notices }
        val rows = notices.flatMap { notice ->
            OwnerAlertRows.expand(OwnerMarketAlertContent.message(notice, settings, zone), routing.resolve(notice.kind))
        }
        val changed = decisions.flatMap { it.next }
        if (changed.isEmpty()) return JobResult.Success

        if (Thread.currentThread().isInterrupted) {
            log.warn("Owner market signal was cancelled before saving state")
            return JobResult.Skipped(CANCELLED)
        }

        val saved = try {
            store.enqueue(changed, rows)
        } catch (exception: Exception) {
            return storeFailure("enqueue", exception)
        }
        if (!saved) {
            log.info("Skipped owner market signal: signal state version changed by another run")
            return JobResult.Skipped(VERSION_CONFLICT)
        }
        notices.forEach {
            log.info("Owner market alert decided: kind={}, eventKey={}", it.kind, OwnerMarketAlertContent.eventKey(it))
        }
        return JobResult.Success
    }

    /** 세 입력 중 하나라도 읽지 못하면 null - 요약만 보류한다. */
    private fun summaryInput(batchMarket: BatchMarket, pair: MarketPair, windows: MarketWindows): DailySummaryInput? {
        val day = attempt("day") {
            aggregations.read(pair, windows.previousDay, AggregationUnit.DAY)
                .firstOrNull { it.observedAt == windows.previousDay.from }
        }
        val rate = attempt("fx") { fx.findLatest(batchMarket.fxBase, batchMarket.fxQuote) }
        val pnl = attempt("pnl") { pnlLatest.find() }
        if (day.isFailure || rate.isFailure || pnl.isFailure) return null
        return DailySummaryInput(day.getOrNull(), batchMarket.fxBase, batchMarket.fxQuote, rate.getOrNull(), pnl.getOrNull())
    }

    private fun <T> observe(source: String, action: () -> T): T? = attempt(source, action).getOrNull()

    /** 읽기 예외는 그 원천만 실패로 본다. 취소는 interrupt 를 복구하고 다시 던진다 (adapter 가 감싼 경우 포함). */
    private fun <T> attempt(source: String, action: () -> T): Result<T> = try {
        Result.success(action())
    } catch (exception: Exception) {
        if (exception.isCancellation()) {
            Thread.currentThread().interrupt()
            throw exception
        }
        // 예외 메시지에는 주소·식별자가 섞일 수 있어 클래스 이름만 남긴다
        log.warn("Owner market observation failed: source={}, error={}", source, exception.javaClass.simpleName)
        Result.failure(exception)
    }

    private fun storeFailure(operation: String, exception: Exception): JobResult {
        log.error("Owner market signal store failed during {}", operation, exception)
        return JobResult.Failure(exception)
    }

    companion object {
        const val CANCELLED = "cancelled"
        const val VERSION_CONFLICT = "state-version-conflict"
    }
}

private fun Throwable.isCancellation(): Boolean =
    generateSequence(this) { current -> current.cause?.takeUnless { it === current } }
        .take(MAX_CAUSE_DEPTH)
        .any { it is InterruptedException || it is CancellationException }

private const val MAX_CAUSE_DEPTH = 16
