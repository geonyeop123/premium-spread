package io.premiumspread.domain.accountpnl

import java.math.BigDecimal
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
    val notionalUsdt: BigDecimal,
    val leverage: Int?,
    val entryFeeUsdt: BigDecimal?,
    val entryFeeKrw: BigDecimal?,
    val exitFeeUsdt: BigDecimal,
    val exitFeeKrw: BigDecimal,
    val source: ForeignPerpPositionSource,
) {
    override fun toString(): String = "ForeignLegBreakdown(source=$source)"
}

/** 수익·정시 알림 "자산" 묶음 값 (#105). 금액은 전체 정밀도(표시 반올림은 템플릿). null = 표시 `없음` 또는 수동 모드 숨김. */
@Suppress("LongParameterList")
data class AccountAssetBreakdown(
    val totalKrw: BigDecimal?,
    val bithumbKrwBalance: BigDecimal?,
    val marginBalanceUsdt: BigDecimal?,
    val marginBalanceKrw: BigDecimal?,
    val availableBalanceUsdt: BigDecimal?,
    val availableBalanceKrw: BigDecimal?,
    val usdtLastPriceKrw: BigDecimal?,
) {
    override fun toString(): String = "AccountAssetBreakdown"

    companion object {
        fun of(
            korea: KoreaSpotAccountSnapshot,
            foreign: ForeignPerpAccountSnapshot,
            koreaValueKrw: BigDecimal,
            fxRate: BigDecimal,
        ): AccountAssetBreakdown {
            val bal = if (foreign.positionSource == ForeignPerpPositionSource.MANUAL_ENV) null else foreign.balance
            val marginKrw = bal?.marginBalanceUsdt?.multiply(fxRate)
            val krwBalance = korea.krwBalance
            val total = if (krwBalance != null && marginKrw != null) {
                koreaValueKrw.add(krwBalance).add(marginKrw)
            } else {
                null
            }
            return AccountAssetBreakdown(
                totalKrw = total,
                bithumbKrwBalance = krwBalance,
                marginBalanceUsdt = bal?.marginBalanceUsdt,
                marginBalanceKrw = marginKrw,
                availableBalanceUsdt = bal?.availableBalanceUsdt,
                availableBalanceKrw = bal?.availableBalanceUsdt?.multiply(fxRate),
                usdtLastPriceKrw = korea.usdtLastPriceKrw,
            )
        }
    }
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
    val assets: AccountAssetBreakdown,
) {
    override fun toString(): String = "AccountPnlBreakdown(calculatedAt=$calculatedAt)"

    companion object {
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
            val foreignNotionalUsdt = foreignExit.multiply(foreignQty)

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
                notionalUsdt = foreignNotionalUsdt,
                leverage = foreignSnapshot.symbolConfig?.leverage,
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
                assets = AccountAssetBreakdown.of(koreaSnapshot, foreignSnapshot, korea.valueKrw, fx),
            )
        }

        private fun premiumPercent(korea: BigDecimal, foreign: BigDecimal, fx: BigDecimal): BigDecimal =
            HedgeQuote.premiumPercent(korea, foreign, fx)
    }
}
