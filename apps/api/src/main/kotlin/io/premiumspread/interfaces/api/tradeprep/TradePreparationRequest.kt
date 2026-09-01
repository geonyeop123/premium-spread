package io.premiumspread.interfaces.api.tradeprep

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.PositiveOrZero
import java.math.BigDecimal

/**
 * 거래 준비 요청 DTO (`.ai/rules/naming.md` inner class 패턴).
 *
 * **owner 필드를 두지 않는다** (design.md D10, `dod.md` AC12). owner 는 인증 principal 에서
 * 도출하며 요청 body 에서 오지 않는다 — body 로 받으면 남의 계정으로 계획을 만드는 경로가 열린다.
 */
class TradePreparationRequest private constructor() {

    /**
     * 준비 계산 요청 (design.md D2·D12).
     *
     * 잔고 경계는 Domain 사이징 관계식의 정의역을 그대로 옮긴다 (`TradePrepSizing`):
     * 한국 잔고는 `0` 이 허용되고(물량이 `0` 이 되어 계획이 만들어지지 않을 뿐이다),
     * 해외 잔고는 `R = B_k / (X · B_b)` 의 분모라 `0` 이면 관계식 자체가 성립하지 않는다.
     * 여기서 막지 않으면 같은 거절이 422 `DOMAIN_ERROR` 로 나가 transport 오류와 구별되지 않는다.
     */
    data class Prepare(
        @field:NotBlank val symbol: String,
        @field:NotBlank val koreaExchange: String,
        @field:NotBlank val foreignExchange: String,
        @field:PositiveOrZero val koreaBalance: BigDecimal,
        @field:Positive val foreignBalance: BigDecimal,
    )

    /**
     * 진입 목표 프리미엄 등록 요청 (design.md D6).
     *
     * 목표 프리미엄에 부호 제약을 두지 않는다 — 역프리미엄(음수) 진입이 정상 시나리오다.
     * 값이 없으면 Kotlin non-null 파라미터라 역직렬화 단계에서 400 이 된다.
     */
    data class RegisterTarget(val desiredEntryPremiumRate: BigDecimal)
}
