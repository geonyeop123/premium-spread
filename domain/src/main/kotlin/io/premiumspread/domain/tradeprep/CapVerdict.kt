package io.premiumspread.domain.tradeprep

import java.math.BigDecimal

/** 위반한 개별 캡 (design.md §3). */
enum class CapViolation {
    /** 바이낸스 레버리지가 캡에 도달하거나 넘었다. */
    LEVERAGE_CAP,

    /** 투입액/총자산(빗썸 비중)이 자본 효율 하한 미만이다. */
    EFFICIENCY_CAP,
}

/**
 * 레버 캡·효율 캡·청산 거리 판정 결과 (design.md §3).
 *
 * 청산 거리(`liquidationDistance`)는 레버리지의 함수(`≈ 1/L`)로, 독립된 임계값을 갖는 별도
 * 캡이 아니라 [LEVERAGE_CAP] 위반과 같은 사건을 다른 지표(가격이 몇 % 움직이면 청산되는지)로
 * 보여주는 관찰값이다. 응답에는 판정과 함께 실려 owner가 위험을 직관적으로 확인한다.
 */
data class CapVerdict(
    val leverage: BigDecimal,
    val koreaShare: BigDecimal,
    val liquidationDistance: BigDecimal,
    val violations: Set<CapViolation>,
) {
    val isViolated: Boolean get() = violations.isNotEmpty()
}
