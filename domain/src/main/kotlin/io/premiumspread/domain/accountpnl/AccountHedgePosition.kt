package io.premiumspread.domain.accountpnl

import java.math.BigDecimal

data class AccountHedgePosition(val korea: KoreaSpotAccountSnapshot, val foreign: ForeignPerpAccountSnapshot) {
    val koreaQuantity: BigDecimal get() = korea.quantity
    val foreignQuantity: BigDecimal get() = foreign.signedQuantity.abs()
    val fingerprint: String
        get() = listOf(
            korea.quantity,
            korea.averageBuyPrice,
            foreign.signedQuantity,
            foreign.entryPrice,
        ).joinToString("|") { it.stripTrailingZeros().toPlainString() }

    companion object {
        val KOREA_DUST: BigDecimal = BigDecimal("0.0001")

        fun resolve(korea: KoreaSpotAccountSnapshot, foreign: ForeignPerpAccountSnapshot): AccountHedgePosition? =
            if (korea.quantity < KOREA_DUST || foreign.signedQuantity.signum() >= 0) {
                null
            } else {
                AccountHedgePosition(korea, foreign)
            }
    }
}
