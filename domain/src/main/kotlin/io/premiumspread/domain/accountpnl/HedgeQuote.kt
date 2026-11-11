package io.premiumspread.domain.accountpnl

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/** 김프·VWAP 공유 계산. HOLDING 과 FLAT 관측이 같은 식을 쓰도록 입력만 받는 순수 함수로 둔다. */
object HedgeQuote {
    private val HUNDRED = BigDecimal(100)
    private const val PERCENT_SCALE = 2

    /** (korea − foreign × fx) / korea × 100, 소수 둘째 자리 HALF_UP. */
    fun premiumPercent(korea: BigDecimal, foreign: BigDecimal, fx: BigDecimal): BigDecimal =
        korea.subtract(foreign.multiply(fx))
            .divide(korea, MathContext.DECIMAL128)
            .multiply(HUNDRED)
            .setScale(PERCENT_SCALE, RoundingMode.HALF_UP)

    /**
     * 앞 레벨부터 [quantity] 만큼 소화한 수량가중 평균가.
     * 깊이가 모자라면 [AccountReadFailureCategory.INSUFFICIENT_DEPTH].
     */
    fun vwap(levels: List<PriceLevel>, quantity: BigDecimal, side: String): BigDecimal {
        var remaining = quantity
        var notional = BigDecimal.ZERO
        for (level in levels) {
            if (remaining.signum() <= 0) break
            val take = remaining.min(level.quantity)
            notional = notional.add(level.price.multiply(take))
            remaining = remaining.subtract(take)
        }
        if (remaining.signum() > 0) {
            throw AccountReadException(
                AccountReadFailureCategory.INSUFFICIENT_DEPTH,
                "$side depth is insufficient for the position quantity",
            )
        }
        return notional.divide(quantity, MathContext.DECIMAL128)
    }
}
