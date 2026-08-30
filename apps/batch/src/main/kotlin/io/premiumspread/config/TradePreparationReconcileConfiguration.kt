package io.premiumspread.config

import io.premiumspread.domain.tradeprep.TradePreparationReconcileService
import io.premiumspread.domain.tradeprep.TradePreparationRepository
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * reconcile 의 Domain capability 배선이다 (design.md D17·D21).
 *
 * [TradePreparationReconcileService] 가 `@Service` 가 아니라 여기서 빈이 되는 이유는
 * [TradePreparationEvaluationConfiguration] 과 같다: `apps:batch` 는
 * `io.premiumspread.domain..*Service` 를 component scan 에서 제외한다
 * (`PremiumSpreadBatchApplication`).
 *
 * 평가와 달리 설정값을 받지 않는다 — reconcile 의 판정 기준은 "결속 스냅샷 id 가 현재 판정용
 * 잔고의 id 와 같은가"뿐이라 임계값이 없다. 주기와 lock 은 `batch.scheduling`·`batch.jobs` 가
 * 이미 소유한다.
 */
@Configuration(proxyBeanMethods = false)
class TradePreparationReconcileConfiguration {

    @Bean
    fun tradePreparationReconcileService(repository: TradePreparationRepository): TradePreparationReconcileService =
        TradePreparationReconcileService(repository)
}
