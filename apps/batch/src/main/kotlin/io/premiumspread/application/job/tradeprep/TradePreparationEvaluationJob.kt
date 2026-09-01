package io.premiumspread.application.job.tradeprep

import io.premiumspread.application.common.DefaultJobConfigProvider
import io.premiumspread.application.common.JobConfigProvider
import io.premiumspread.application.common.JobExecutor
import io.premiumspread.application.common.JobResult
import io.premiumspread.domain.batch.BatchMarketProvider
import io.premiumspread.domain.job.JobId
import io.premiumspread.domain.premium.PremiumReadPort
import io.premiumspread.domain.tradeprep.TradePreparationEvaluationOutcome
import io.premiumspread.domain.tradeprep.TradePreparationEvaluationService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Clock

/**
 * `WATCHING` 계획의 조건을 현재 프리미엄으로 평가하는 Job 이다 (design.md D14·D19·D21,
 * `dod.md` AC17).
 *
 * **새 수집을 만들지 않는다.** batch 가 이미 계산해 둔 현재 프리미엄을 [PremiumReadPort] 로 읽어
 * Domain 평가에 넘길 뿐이다.
 *
 * **판정을 소유하지 않는다.** 신선도·`MarketPair` 일치·조건 충족·`ARMED` 전이는 전부
 * [TradePreparationEvaluationService] 가 소유한다 (D21). 이 Job 은 읽기 port 와 그 capability 를
 * 조합하고 결과를 `JobResult` 로 옮기기만 한다 — 결속 잔고의 검증 수준을 다시 계산하거나
 * 덮어쓰지 않는다.
 *
 * **주문을 제출하지 않는다.** `ARMED` 가 이 단위의 종점이다.
 */
@Component
class TradePreparationEvaluationJob(
    private val premiumReader: PremiumReadPort,
    private val evaluationService: TradePreparationEvaluationService,
    private val marketProvider: BatchMarketProvider,
    private val executor: JobExecutor,
    private val clock: Clock,
    private val jobConfigs: JobConfigProvider = DefaultJobConfigProvider,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun run(): JobResult = executor.execute(jobConfigs.get(JobId.TRADE_PREPARATION_EVALUATION)) {
        evaluate()
    }

    private fun evaluate(): JobResult = try {
        val pair = marketProvider.defaultMarket().pair
        val summary = evaluationService.evaluate(pair, premiumReader.findLatest(pair), clock.instant())

        if (summary.outcome == TradePreparationEvaluationOutcome.EVALUATED) {
            log.debug(
                "Evaluated {} watching plans for {} (armed={}, observedOnly={})",
                summary.evaluated,
                pair,
                summary.armed,
                summary.observedOnly,
            )
            JobResult.Success
        } else {
            // 계획을 무효화하지 않는다 (D14). stream 이 회복되면 다음 실행이 그대로 재개한다.
            log.debug("Skipped trade preparation evaluation for {}: {}", pair, summary.outcome)
            JobResult.Skipped(summary.outcome.name.lowercase())
        }
    } catch (e: Exception) {
        log.error("Failed to evaluate trade preparation conditions", e)
        JobResult.Failure(e)
    }
}
