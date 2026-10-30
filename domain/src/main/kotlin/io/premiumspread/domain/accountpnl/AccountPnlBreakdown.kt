package io.premiumspread.domain.accountpnl

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.Instant

/** 빗썸 현물 leg 표시값. 금액은 전체 정밀도(표시 반올림은 템플릿). */
data class KoreaLegBreakdown(
    val quantity: BigDecimal,
    val averagePrice: BigDecimal,
    val currentPrice: BigDecimal,
    val pnlKrw: BigDecimal,
    val valueKrw: BigDecimal,
    val entryFeeKrw: BigDecimal?,
    val exitFeeKrw: BigDecimal,
) {
    override fun toString(): String = "KoreaLegBreakdown"
}

/**
 * 바이낸스 선물 숏 leg 표시값. quantity = |signedQuantity|.
 * 방향 필드는 없다 — AccountHedgePosition.resolve 가 signedQuantity < 0(숏)일 때만 포지션을 만든다(design §6.1).
 */
data class ForeignLegBreakdown(
    val quantity: BigDecimal,
    val entryPrice: BigDecimal,
    val currentPrice: BigDecimal,
    val pnlUsdt: BigDecimal,
    val pnlKrw: BigDecimal,
    val valueUsdt: BigDecimal,
    val valueKrw: BigDecimal,
    val entryFeeUsdt: BigDecimal?,
    val entryFeeKrw: BigDecimal?,
    val exitFeeUsdt: BigDecimal,
    val exitFeeKrw: BigDecimal,
    val source: ForeignPerpPositionSource,
) {
    override fun toString(): String = "ForeignLegBreakdown(source=$source)"
}

/** 수익·정시 알림의 값 (design §6.1). 실계정 금액이므로 toString 은 calculatedAt 만. */
@Suppress("LongParameterList")
data class AccountPnlBreakdown(
    val currentPremiumPercent: BigDecimal,
    val entryPremiumPercent: BigDecimal,
    val premiumGapPercentPoint: BigDecimal,
    val korea: KoreaLegBreakdown,
    val foreign: ForeignLegBreakdown,
    val grossKrw: BigDecimal,
    val feeTotalKrw: BigDecimal,
    val fundingUsdt: BigDecimal?,
    val fundingKrw: BigDecimal,
    val netKrw: BigDecimal,
    val netPercent: BigDecimal,
    val fxRate: BigDecimal,
    val calculatedAt: Instant,
) {
    override fun toString(): String = "AccountPnlBreakdown(calculatedAt=$calculatedAt)"

    companion object {
        private val HUNDRED = BigDecimal(100)
        private const val PERCENT_SCALE = 2

        fun of(pnl: AccountNetPnl): AccountPnlBreakdown {
            val position = pnl.position
            val koreaSnapshot = position.korea
            val foreignSnapshot = position.foreign
            val fx = pnl.fxRate
            val koreaQty = position.koreaQuantity
            val foreignQty = position.foreignQuantity
            val koreaExit = pnl.koreaExitPrice
            val foreignExit = pnl.foreignExitPrice

            val currentPremium = premiumPercent(koreaExit, foreignExit, fx)
            val entryPremium = premiumPercent(koreaSnapshot.averageBuyPrice, foreignSnapshot.entryPrice, fx)

            val koreaExitFee = koreaSnapshot.sellFeeRate.multiply(koreaExit).multiply(koreaQty)
            val foreignExitFeeUsdt = foreignSnapshot.takerFeeRate.multiply(foreignExit).multiply(foreignQty)
            val foreignPnlUsdt = foreignSnapshot.entryPrice.subtract(foreignExit).multiply(foreignQty)
            val foreignValueUsdt = foreignExit.multiply(foreignQty)

            val korea = KoreaLegBreakdown(
                quantity = koreaQty,
                averagePrice = koreaSnapshot.averageBuyPrice,
                currentPrice = koreaExit,
                pnlKrw = koreaExit.subtract(koreaSnapshot.averageBuyPrice).multiply(koreaQty),
                valueKrw = koreaExit.multiply(koreaQty),
                entryFeeKrw = koreaSnapshot.entryFeeKrw,
                exitFeeKrw = koreaExitFee,
            )
            val foreign = ForeignLegBreakdown(
                quantity = foreignQty,
                entryPrice = foreignSnapshot.entryPrice,
                currentPrice = foreignExit,
                pnlUsdt = foreignPnlUsdt,
                pnlKrw = foreignPnlUsdt.multiply(fx),
                valueUsdt = foreignValueUsdt,
                valueKrw = foreignValueUsdt.multiply(fx),
                entryFeeUsdt = foreignSnapshot.entryCommissionUsdt,
                entryFeeKrw = foreignSnapshot.entryCommissionUsdt?.multiply(fx),
                exitFeeUsdt = foreignExitFeeUsdt,
                exitFeeKrw = foreignExitFeeUsdt.multiply(fx),
                source = foreignSnapshot.positionSource,
            )
            val feeTotal = (korea.entryFeeKrw ?: BigDecimal.ZERO)
                .add(foreign.entryFeeKrw ?: BigDecimal.ZERO)
                .add(korea.exitFeeKrw)
                .add(foreign.exitFeeKrw)

            return AccountPnlBreakdown(
                currentPremiumPercent = currentPremium,
                entryPremiumPercent = entryPremium,
                premiumGapPercentPoint = currentPremium.subtract(entryPremium),
                korea = korea,
                foreign = foreign,
                grossKrw = pnl.grossKrw,
                feeTotalKrw = feeTotal,
                fundingUsdt = foreignSnapshot.fundingUsdt,
                fundingKrw = pnl.fundingKrw,
                netKrw = pnl.netKrw,
                netPercent = pnl.netPercent,
                fxRate = fx,
                calculatedAt = pnl.calculatedAt,
            )
        }

        /** (korea − foreign × fx) / korea × 100, 소수 둘째 자리 HALF_UP. */
        private fun premiumPercent(korea: BigDecimal, foreign: BigDecimal, fx: BigDecimal): BigDecimal =
            korea.subtract(foreign.multiply(fx))
                .divide(korea, MathContext.DECIMAL128)
                .multiply(HUNDRED)
                .setScale(PERCENT_SCALE, RoundingMode.HALF_UP)
    }
}
