package io.premiumspread.domain.accountpnl

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * 확정 손익 계산 (design D4). 매번 창 안 체결 전체를 다시 계산하는 순수 함수다 — 앞선 값에 증분을 더하지 않는다.
 * 같은 체결이 겹치는 창·페이지로 다시 와도 id 로 한 번만 센다.
 */
object RealizedPnlPolicy {

    /**
     * 빗썸 이동평균 원가(매수 수수료 포함). 체결 시각·id 순으로 적용한다.
     * 보유 0 에서 dust 이상 매도하거나 보유보다 dust 이상 더 팔면 원가를 정할 수 없어 null,
     * 보유 0 에서 dust 미만 매도(앞 건 잔량)는 원가 0 으로 매도 순액을 실현한다.
     * 끝에서 `|Σbuy − Σsell − expected| >= KOREA_DUST` 이면 null.
     */
    fun korea(fills: List<KoreaSpotFill>, expectedQuantity: BigDecimal): KoreaRealized? {
        val ordered = fills.distinctBy { it.id }.sortedWith(compareBy<KoreaSpotFill>({ it.filledAt }, { it.id }))
        var book = MovingAverageBook.EMPTY
        for (fill in ordered) {
            book = book.apply(fill) ?: return null
        }
        val net = ordered.fold(BigDecimal.ZERO) { acc, f -> if (f.side == FillSide.BUY) acc + f.quantity else acc - f.quantity }
        return if ((net - expectedQuantity).abs() < AccountHedgePosition.KOREA_DUST) KoreaRealized(book.realized) else null
    }

    /** 바이낸스. 순 = Σ realizedPnl − Σ |commission| + Σ funding. 부호 수량 합이 기대와 다르면(compareTo) null. */
    fun foreign(
        fills: List<ForeignPerpFill>,
        funding: List<ForeignFunding>,
        expectedSignedQuantity: BigDecimal,
    ): ForeignRealized? {
        val distinctFills = fills.distinctBy { it.id }
        val net = distinctFills.fold(BigDecimal.ZERO) { acc, f -> acc + f.signedQuantity }
        if (net.compareTo(expectedSignedQuantity) != 0) return null
        return ForeignRealized(
            realizedPnlUsdt = distinctFills.fold(BigDecimal.ZERO) { acc, f -> acc + f.realizedPnlUsdt },
            commissionUsdt = distinctFills.fold(BigDecimal.ZERO) { acc, f -> acc + f.commissionUsdt.abs() },
            fundingUsdt = funding.distinctBy { it.id }.fold(BigDecimal.ZERO) { acc, f -> acc + f.incomeUsdt },
        )
    }

    /** 빗썸 실현 + 바이낸스 순 × 환율. 원 단위 HALF_UP 은 여기서 한 번만 한다. */
    fun totalKrw(korea: KoreaRealized, foreign: ForeignRealized, fxRate: BigDecimal): BigDecimal =
        korea.realizedKrw.add(foreign.netUsdt.multiply(fxRate)).setScale(0, RoundingMode.HALF_UP)

    /** 이동평균 장부. [cost] 는 보유 [quantity] 의 원가 합(수수료 포함). 덧셈·뺄셈은 정확하고 나눗셈만 DECIMAL128 이다. */
    private data class MovingAverageBook(val quantity: BigDecimal, val cost: BigDecimal, val realized: BigDecimal) {
        fun apply(fill: KoreaSpotFill): MovingAverageBook? = when (fill.side) {
            FillSide.BUY -> copy(quantity = quantity + fill.quantity, cost = cost + fill.fundsKrw + fill.feeKrw)
            FillSide.SELL -> sell(fill)
        }

        private fun sell(fill: KoreaSpotFill): MovingAverageBook? {
            // 보유 0 에서 dust 미만 매도는 앞 건의 dust 잔량을 판 것이다 — 원가 0 으로 매도 순액을 실현한다.
            if (quantity.signum() <= 0) {
                val dust = fill.quantity < AccountHedgePosition.KOREA_DUST
                return if (dust) copy(realized = realized + (fill.fundsKrw - fill.feeKrw)) else null
            }
            if (fill.quantity - quantity >= AccountHedgePosition.KOREA_DUST) return null
            // 보유보다 dust 미만 더 팔면 보유분 전부의 원가를 뺀다(남은 보유는 0).
            val soldQuantity = fill.quantity.min(quantity)
            val soldCost = if (soldQuantity.compareTo(quantity) == 0) {
                cost
            } else {
                soldQuantity.multiply(cost).divide(quantity, MathContext.DECIMAL128)
            }
            return MovingAverageBook(
                quantity = quantity - soldQuantity,
                cost = cost - soldCost,
                realized = realized + (fill.fundsKrw - fill.feeKrw) - soldCost,
            )
        }

        companion object {
            val EMPTY = MovingAverageBook(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO)
        }

        override fun toString(): String = "MovingAverageBook"
    }
}
