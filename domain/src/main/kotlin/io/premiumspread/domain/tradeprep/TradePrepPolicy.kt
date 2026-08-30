package io.premiumspread.domain.tradeprep

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 레버 캡·효율 캡과 거래소 lot/step 값을 설정으로 받아 판정한다 (design.md §3, D12).
 *
 * 값을 하드코딩하지 않는다 — 전부 생성자 파라미터다. Spring 설정 바인딩(properties → 이 클래스)은
 * 이 태스크 범위 밖이다.
 *
 * @param leverageCap 바이낸스 레버리지 상한 (예: `7`). "도달"하면 위반이다 — 경계값은 위반에 포함된다.
 * @param efficiencyFloor 빗썸 비중(투입액/총자산)의 하한. `0`과 `1` 사이의 fraction (예: `0.60`).
 *   "미만"이면 위반이다 — 경계값 자체는 위반이 아니다.
 * @param koreaLotSize 빗썸 최소 주문 수량 단위(lot/step). 이 배수로 내림한다.
 * @param foreignLotSize 바이낸스 최소 주문 수량 단위(lot/step). 이 배수로 내림한다.
 */
class TradePrepPolicy(
    val leverageCap: BigDecimal,
    val efficiencyFloor: BigDecimal,
    val koreaLotSize: BigDecimal,
    val foreignLotSize: BigDecimal,
) {
    init {
        require(leverageCap > BigDecimal.ZERO) { "Leverage cap must be positive: $leverageCap" }
        require(efficiencyFloor > BigDecimal.ZERO) { "Efficiency floor must be positive: $efficiencyFloor" }
        require(koreaLotSize > BigDecimal.ZERO) { "Korea lot size must be positive: $koreaLotSize" }
        require(foreignLotSize > BigDecimal.ZERO) { "Foreign lot size must be positive: $foreignLotSize" }
    }

    /** 빗썸 lot size 배수로 보수적(내림) 반올림한다. 물량을 늘리지 않는다 (D12). */
    fun roundKoreaQuantity(quantity: BigDecimal): BigDecimal = floorToLot(quantity, koreaLotSize)

    /** 바이낸스 lot size 배수로 보수적(내림) 반올림한다. 물량을 늘리지 않는다 (D12). */
    fun roundForeignQuantity(quantity: BigDecimal): BigDecimal = floorToLot(quantity, foreignLotSize)

    /**
     * 레버 캡·효율 캡을 판정하고 청산 거리(`≈ 1/L`)를 관찰값으로 담는다.
     *
     * 레버 캡은 "도달"(ECO-5 §1)이 곧 정지 조건이므로 경계값을 포함한다(`>=`).
     * 효율 캡은 "미만"(ECO-5 §1)이 정지 조건이므로 경계값 자체는 위반이 아니다(`<`).
     */
    fun judge(leverage: BigDecimal, koreaShare: BigDecimal): CapVerdict {
        val violations = buildSet {
            if (leverage >= leverageCap) add(CapViolation.LEVERAGE_CAP)
            if (koreaShare < efficiencyFloor) add(CapViolation.EFFICIENCY_CAP)
        }
        val liquidationDistance =
            if (leverage > BigDecimal.ZERO) {
                BigDecimal.ONE.divide(leverage, TradePrepSizing.CALCULATION_SCALE, RoundingMode.HALF_UP)
            } else {
                BigDecimal.ZERO
            }
        return CapVerdict(
            leverage = leverage,
            koreaShare = koreaShare,
            liquidationDistance = liquidationDistance,
            violations = violations,
        )
    }

    private fun floorToLot(quantity: BigDecimal, lotSize: BigDecimal): BigDecimal {
        if (quantity <= BigDecimal.ZERO) return BigDecimal.ZERO
        return quantity.divideToIntegralValue(lotSize).multiply(lotSize)
    }
}
