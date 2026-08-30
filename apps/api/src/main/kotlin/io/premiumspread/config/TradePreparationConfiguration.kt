package io.premiumspread.config

import io.premiumspread.domain.tradeprep.TradePrepPolicy
import io.premiumspread.domain.tradeprep.TradePreparationOwnerPolicy
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * `TradePrepPolicy` 와 `TradePreparationOwnerPolicy` 배선. 값은 [TradePreparationProperties] 와
 * [TradePreparationOwnerProperties] 가 소유하고 이 클래스는 그것을 Domain 값 객체로 옮기기만 한다
 * — 판정 로직은 Domain 에 있다.
 *
 * 잔고 조회 port 는 여기서 배선하지 않는다. production 에 `VerifiedBalanceReadPort` 구현이
 * 존재하는 것 자체가 D9 의 신뢰 경계를 무너뜨리므로(D22, `dod.md` AC20) 빈을 만들지 않는다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TradePreparationProperties::class, TradePreparationOwnerProperties::class)
class TradePreparationConfiguration {

    @Bean
    fun tradePrepPolicy(properties: TradePreparationProperties): TradePrepPolicy = properties.toPolicy()

    @Bean
    fun tradePreparationOwnerPolicy(properties: TradePreparationOwnerProperties): TradePreparationOwnerPolicy =
        properties.toPolicy()
}
