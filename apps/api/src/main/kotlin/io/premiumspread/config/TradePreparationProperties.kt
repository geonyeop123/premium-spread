package io.premiumspread.config

import io.premiumspread.domain.tradeprep.TradePrepPolicy
import jakarta.validation.constraints.DecimalMin
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.math.BigDecimal

/**
 * 캡과 거래소 lot/step 값을 설정으로 받는다 (design.md §3 "이 설계는 값을 정하지 않고 설정으로
 * 받는다", `TradePrepPolicy` KDoc).
 *
 * 캡 값의 출처는 `ECO-5` §7 이 소유한다 — 레버 캡 7배·효율 캡 60% 둘 다 owner 결정이다.
 * lot/step 값은 `TP-OPEN-7` 미해결이라 거래소 `exchangeInfo` 로 확인되면 설정만 바꾼다.
 */
@Validated
@ConfigurationProperties(prefix = "trade-preparation")
data class TradePreparationProperties(
    @field:DecimalMin(value = "0", inclusive = false)
    val leverageCap: BigDecimal = BigDecimal("7"),
    @field:DecimalMin(value = "0", inclusive = false)
    val efficiencyFloor: BigDecimal = BigDecimal("0.60"),
    @field:DecimalMin(value = "0", inclusive = false)
    val koreaLotSize: BigDecimal = BigDecimal("0.0001"),
    @field:DecimalMin(value = "0", inclusive = false)
    val foreignLotSize: BigDecimal = BigDecimal("0.001"),
) {
    fun toPolicy(): TradePrepPolicy = TradePrepPolicy(
        leverageCap = leverageCap,
        efficiencyFloor = efficiencyFloor,
        koreaLotSize = koreaLotSize,
        foreignLotSize = foreignLotSize,
    )
}
