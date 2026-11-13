package io.premiumspread.domain.accountpnl

import java.math.BigDecimal

/** 바이낸스 레그 한 회차 관측. source 는 항상 EXCHANGE_API 라 필드가 없다. 실계정 값이므로 toString 은 고정 문자열. */
@Suppress("LongParameterList")
data class ForeignLegObservation(
    val leverage: Int?,
    val marginType: ForeignPerpMarginType?,
    val liquidationPrice: BigDecimal?,
    val markPrice: BigDecimal?,
    val isolatedMargin: BigDecimal?,
    val maintMargin: BigDecimal?,
    val entryCommissionUsdt: BigDecimal?,
    val fundingUsdt: BigDecimal?,
) {
    override fun toString(): String = "ForeignLegObservation"
}

data class KoreaLegObservation(val entryFeeKrw: BigDecimal?) {
    override fun toString(): String = "KoreaLegObservation"
}

/** HOLDING 관측과 같은 회차의 레그 관측. 레그가 없으면 null (스냅샷 값이 없는 것과 구분된다). */
data class LegObservations(val korea: KoreaLegObservation?, val foreign: ForeignLegObservation?) {
    override fun toString(): String = "LegObservations"

    companion object {
        val NONE = LegObservations(null, null)

        /** 빗썸은 수량 0, 바이낸스는 수동 환경변수 포지션이거나 수량 0 이면 레그가 없다. 없는 값은 0 으로 바꾸지 않는다. */
        fun of(korea: KoreaSpotAccountSnapshot, foreign: ForeignPerpAccountSnapshot): LegObservations {
            val koreaLeg =
                if (korea.quantity.compareTo(BigDecimal.ZERO) == 0) null else KoreaLegObservation(korea.entryFeeKrw)
            val foreignLeg =
                if (foreign.positionSource == ForeignPerpPositionSource.MANUAL_ENV ||
                    foreign.signedQuantity.compareTo(BigDecimal.ZERO) == 0
                ) {
                    null
                } else {
                    ForeignLegObservation(
                        leverage = foreign.symbolConfig?.leverage,
                        marginType = foreign.symbolConfig?.marginType,
                        liquidationPrice = foreign.positionRisk?.liquidationPrice,
                        markPrice = foreign.positionRisk?.markPrice,
                        isolatedMargin = foreign.positionRisk?.isolatedMargin,
                        maintMargin = foreign.positionRisk?.maintMargin,
                        entryCommissionUsdt = foreign.entryCommissionUsdt,
                        fundingUsdt = foreign.fundingUsdt,
                    )
                }
            return if (koreaLeg == null && foreignLeg == null) NONE else LegObservations(koreaLeg, foreignLeg)
        }
    }
}
