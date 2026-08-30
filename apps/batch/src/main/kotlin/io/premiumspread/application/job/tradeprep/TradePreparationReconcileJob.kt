package io.premiumspread.application.job.tradeprep

import io.premiumspread.application.common.DefaultJobConfigProvider
import io.premiumspread.application.common.JobConfigProvider
import io.premiumspread.application.common.JobExecutor
import io.premiumspread.application.common.JobResult
import io.premiumspread.domain.job.JobId
import io.premiumspread.domain.tradeprep.TradePreparationReconcileOutcome
import io.premiumspread.domain.tradeprep.TradePreparationReconcileService
import io.premiumspread.domain.tradeprep.TradePreparationReconcileSummary
import io.premiumspread.domain.tradeprep.VerifiedBalanceReadPort
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component
import java.time.Clock

/**
 * 활성(`WATCHING`·`ARMED`) 계획의 결속 잔고 스냅샷을 현재 판정용 잔고와 주기적으로 대조하는
 * Job 이다 (design.md D5·D17, `dod.md` AC18). 세 번째 무효화 producer 다 — 나머지 둘(체결·owner
 * refresh)은 `apps:api` 가 같은 트랜잭션 안에서 수행한다 (D4).
 *
 * **판정을 소유하지 않는다.** 대조와 전이는 [TradePreparationReconcileService] 와
 * `TradePreparation.invalidateOnReconcileMismatch` 가 소유한다 (D21). 이 Job 은 판정용 port 를
 * 해석하고 결과를 `JobResult` 로 옮긴다.
 *
 * ## 판정용 원천은 선택적 주입이다 (D22)
 *
 * production 에는 [VerifiedBalanceReadPort] 구현이 **0개**다 — declared 신고값으로는 판정용
 * 잔고에 도달할 수 없고(`VerifiedBalance.from`), 실원천은 `ExchangeBalanceAdapter`(`ACT-2` 이후)가
 * 들어올 때 생긴다. 그래서 `apps:api` 의 `TradePreparationFacade` 와 같은
 * `ObjectProvider` + `getIfAvailable()` 형태를 쓴다.
 *
 * **원천이 없으면 skipped 다. failure 가 아니다** — 설정이 아직 안 된 것이지 실행이 실패한 게
 * 아니다. **계획을 무효화하지도 않는다**: 대조하지 못한 것과 불일치를 발견한 것은 다른 사실이며,
 * "못 읽었으니 안전하게 무효화"는 owner 의 계획을 미배선만으로 매 주기 지운다.
 */
@Component
class TradePreparationReconcileJob(
    private val verifiedBalanceReadPort: ObjectProvider<VerifiedBalanceReadPort>,
    private val reconcileService: TradePreparationReconcileService,
    private val executor: JobExecutor,
    private val clock: Clock,
    private val jobConfigs: JobConfigProvider = DefaultJobConfigProvider,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun run(): JobResult = executor.execute(jobConfigs.get(JobId.TRADE_PREPARATION_RECONCILE)) {
        reconcile()
    }

    private fun reconcile(): JobResult = try {
        val source = verifiedBalanceReadPort.getIfAvailable()
        if (source == null) {
            log.debug("Skipped trade preparation reconcile: no verified balance source is wired")
            JobResult.Skipped(TradePreparationReconcileOutcome.BALANCE_SOURCE_UNAVAILABLE.skipReason())
        } else {
            // port 를 그대로 넘긴다. 여기서 findForDecision() 을 부르면 @Transactional 프록시
            // **밖에서** 잔고를 읽게 되고, 그 뒤 커밋된 계획이 옛 잔고와 대조돼 무효화된다.
            report(reconcileService.reconcile(source, clock.instant()))
        }
    } catch (e: Exception) {
        log.error("Failed to reconcile trade preparation balance bindings", e)
        JobResult.Failure(e)
    }

    private fun report(summary: TradePreparationReconcileSummary): JobResult =
        if (summary.outcome == TradePreparationReconcileOutcome.RECONCILED) {
            log.debug("Reconciled {} active plans (invalidated={})", summary.examined, summary.invalidated)
            JobResult.Success
        } else {
            log.debug("Skipped trade preparation reconcile: {}", summary.outcome)
            JobResult.Skipped(summary.outcome.skipReason())
        }
}

/** bounded skip 사유다 — `JobExecutor` 가 `detail` 로 기록하므로 유한한 값만 넘긴다. */
private fun TradePreparationReconcileOutcome.skipReason(): String = name.lowercase()
