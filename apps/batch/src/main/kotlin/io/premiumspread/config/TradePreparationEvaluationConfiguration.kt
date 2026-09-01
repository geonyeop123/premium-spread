package io.premiumspread.config

import io.premiumspread.domain.tradeprep.TradePreparationEvaluationService
import io.premiumspread.domain.tradeprep.TradePreparationFreshnessPolicy
import io.premiumspread.domain.tradeprep.TradePreparationRepository
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * 조건 평가의 Domain capability 배선이다 (design.md D21).
 *
 * 판정 로직은 전부 Domain 에 있고 이 클래스는 설정값을 Domain 값 객체로 옮겨 서비스를 조립하기만
 * 한다 — `apps:api` 의 `TradePreparationConfiguration` 이 `TradePrepPolicy` 를 배선하는 것과 같은
 * 형태다.
 *
 * [TradePreparationEvaluationService] 가 `@Service` 가 아니라 여기서 빈이 되는 이유: batch 는
 * `io.premiumspread.domain..*Service` 를 component scan 에서 제외하고(`PremiumSpreadBatchApplication`),
 * 평가에 필요한 신선도 설정은 batch 만 소유한다. 스캔으로 배선하면 `apps:api` context 가 쓰지도
 * 않는 설정을 요구하게 된다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TradePreparationEvaluationProperties::class)
class TradePreparationEvaluationConfiguration {

    @Bean
    fun tradePreparationFreshnessPolicy(properties: TradePreparationEvaluationProperties): TradePreparationFreshnessPolicy =
        properties.toPolicy()

    @Bean
    fun tradePreparationEvaluationService(
        repository: TradePreparationRepository,
        freshness: TradePreparationFreshnessPolicy,
    ): TradePreparationEvaluationService = TradePreparationEvaluationService(repository, freshness)
}
