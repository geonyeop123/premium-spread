package io.premiumspread.config

import io.premiumspread.domain.tradeprep.TradePreparationOwnerPolicy
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

/**
 * 거래 준비 계획을 만들 수 있는 owner 를 설정으로 받는다 (design.md D10, `dod.md` AC12).
 *
 * 캡·lot size 를 담은 [TradePreparationProperties] 와 나눠 두는 이유는 값의 출처가 다르기
 * 때문이다 — 캡은 `ECO-5` §7 의 경제 판단이고 이 목록은 배포 단위의 인가 설정이다.
 * `apps:batch` 가 `trade-preparation.evaluation` 을 별도 properties 로 두는 것과 같은 형태다.
 *
 * 기본값이 비어 있으므로 **설정하지 않은 배포에서는 아무도 계획을 만들 수 없다.** 그 판단의
 * 근거는 [TradePreparationOwnerPolicy] 가 소유한다.
 */
@Validated
@ConfigurationProperties(prefix = "trade-preparation.owner")
data class TradePreparationOwnerProperties(val allowedEmails: Set<String> = emptySet()) {

    fun toPolicy(): TradePreparationOwnerPolicy = TradePreparationOwnerPolicy(allowedEmails)
}
