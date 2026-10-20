package io.premiumspread.domain.accountpnl

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.Instant

/**
 * 실계정 김프 포지션의 "지금 양쪽을 청산하면 남는 돈" (design §3).
 *
 * - 호가 기준([netKrw]·[netPercent]): 청산가 = 빗썸 매수호가·바이낸스 매도호가를 각 leg 수량만큼 소화한 VWAP. 판정용.
 * - 체결가 기준([netKrwAtLast]·[netPercentAtLast]): 같은 공식에서 청산가(gross·exitFee 모두)만 최근 체결가. 대조용.
 *
 * 계산 중에는 반올림하지 않는다. 나눗셈만 [MathContext.DECIMAL128] 이고, `netPercent*` 만 소수 둘째 자리 HALF_UP 이다.
 * 금액 필드는 전체 정밀도를 유지하며 표시 반올림은 호출자가 한다.
 */
@Suppress("LongParameterList")
data class AccountNetPnl(
    val grossKrw: BigDecimal,
    val entryFeeKrw: BigDecimal,
    val fundingKrw: BigDecimal,
    val exitFeeEstimateKrw: BigDecimal,
    val netKrw: BigDecimal,
    val netPercent: BigDecimal,
    val grossKrwAtLast: BigDecimal,
    val exitFeeEstimateKrwAtLast: BigDecimal,
    val netKrwAtLast: BigDecimal,
    val netPercentAtLast: BigDecimal,
    val koreaExitPrice: BigDecimal,
    val foreignExitPrice: BigDecimal,
    val koreaEntryFeeUnrecovered: Boolean,
    val foreignEntryFeeUnrecovered: Boolean,
    val fundingUnrecovered: Boolean,
    val position: AccountHedgePosition,
    val fxRate: BigDecimal,
    val fxObservedAt: Instant,
    val calculatedAt: Instant,
) {
    companion object {
        private val HUNDRED = BigDecimal(100)
        private const val PERCENT_SCALE = 2

        fun calculate(
            position: AccountHedgePosition,
            fxRate: BigDecimal,
            fxObservedAt: Instant,
            calculatedAt: Instant,
        ): AccountNetPnl {
            val korea = position.korea
            val foreign = position.foreign
            val koreaQuantity = position.koreaQuantity
            val foreignQuantity = position.foreignQuantity

            val koreaExitPrice = vwap(korea.bidLevels, koreaQuantity, "bithumb bid")
            val foreignExitPrice = vwap(foreign.askLevels, foreignQuantity, "binance ask")

            // 미확보(null) 항목은 0 으로 계산하고 플래그를 남긴다 (design §3.1)
            val entryFeeKrw = (korea.entryFeeKrw ?: BigDecimal.ZERO)
                .add((foreign.entryCommissionUsdt ?: BigDecimal.ZERO).multiply(fxRate))
            val fundingKrw = (foreign.fundingUsdt ?: BigDecimal.ZERO).multiply(fxRate)

            val atQuote = Leg.of(position, koreaExitPrice, foreignExitPrice, fxRate, entryFeeKrw, fundingKrw)
            val atLast = Leg.of(position, korea.lastPrice, foreign.lastPrice, fxRate, entryFeeKrw, fundingKrw)

            return AccountNetPnl(
                grossKrw = atQuote.gross,
                entryFeeKrw = entryFeeKrw,
                fundingKrw = fundingKrw,
                exitFeeEstimateKrw = atQuote.exitFee,
                netKrw = atQuote.net,
                netPercent = atQuote.netPercent,
                grossKrwAtLast = atLast.gross,
                exitFeeEstimateKrwAtLast = atLast.exitFee,
                netKrwAtLast = atLast.net,
                netPercentAtLast = atLast.netPercent,
                koreaExitPrice = koreaExitPrice,
                foreignExitPrice = foreignExitPrice,
                koreaEntryFeeUnrecovered = korea.entryFeeKrw == null,
                foreignEntryFeeUnrecovered = foreign.entryCommissionUsdt == null,
                fundingUnrecovered = foreign.fundingUsdt == null,
                position = position,
                fxRate = fxRate,
                fxObservedAt = fxObservedAt,
                calculatedAt = calculatedAt,
            )
        }

        /**
         * 앞 레벨부터 [quantity] 만큼 소화한 수량가중 평균가.
         * 깊이가 모자라면 [AccountReadFailureCategory.INSUFFICIENT_DEPTH].
         */
        private fun vwap(levels: List<PriceLevel>, quantity: BigDecimal, side: String): BigDecimal {
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

    private data class Leg(
        val gross: BigDecimal,
        val exitFee: BigDecimal,
        val net: BigDecimal,
        val netPercent: BigDecimal,
    ) {
        companion object {
            fun of(
                position: AccountHedgePosition,
                koreaExitPrice: BigDecimal,
                foreignExitPrice: BigDecimal,
                fxRate: BigDecimal,
                entryFeeKrw: BigDecimal,
                fundingKrw: BigDecimal,
            ): Leg {
                val koreaQuantity = position.koreaQuantity
                val foreignQuantity = position.foreignQuantity
                val koreaNotional = koreaExitPrice.multiply(koreaQuantity)
                val foreignNotionalKrw = foreignExitPrice.multiply(foreignQuantity).multiply(fxRate)

                // 기존 Tracking.grossPnl 과 같은 공식
                val koreaLeg = koreaExitPrice.subtract(position.korea.averageBuyPrice).multiply(koreaQuantity)
                val foreignLeg = position.foreign.entryPrice.subtract(foreignExitPrice)
                    .multiply(foreignQuantity)
                    .multiply(fxRate)
                val gross = koreaLeg.add(foreignLeg)
                val exitFee = position.korea.sellFeeRate.multiply(koreaNotional)
                    .add(position.foreign.takerFeeRate.multiply(foreignNotionalKrw))
                val net = gross.subtract(entryFeeKrw).add(fundingKrw).subtract(exitFee)
                val netPercent = net.divide(koreaNotional, MathContext.DECIMAL128)
                    .multiply(HUNDRED)
                    .setScale(PERCENT_SCALE, RoundingMode.HALF_UP)
                return Leg(gross, exitFee, net, netPercent)
            }
        }
    }
}
