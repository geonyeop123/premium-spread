package io.premiumspread.domain.accountpnl

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

class PositionLegObservationTest {
    private val at = Instant.parse("2026-01-01T00:00:00Z")

    private fun korea(quantity: String = "1.5", entryFeeKrw: BigDecimal? = BigDecimal("1234.5678")) =
        KoreaSpotAccountSnapshot(
            quantity = BigDecimal(quantity),
            averageBuyPrice = BigDecimal("100000000"),
            averageBuyPriceModified = false,
            entryFeeKrw = entryFeeKrw,
            sellFeeRate = BigDecimal("0.0004"),
            bidLevels = listOf(PriceLevel(BigDecimal("100000000"), BigDecimal("10"))),
            lastPrice = BigDecimal("100000000"),
            bidObservedAt = at,
        )

    private fun risk(liquidationPrice: BigDecimal? = BigDecimal("41000.12")) =
        ForeignPerpPositionRisk(
            liquidationPrice = liquidationPrice,
            markPrice = BigDecimal("65000.5"),
            isolatedMargin = BigDecimal("321.987"),
            isolatedWallet = BigDecimal("330"),
            maintMargin = BigDecimal("12.25"),
            positionInitialMargin = BigDecimal("300"),
            notional = BigDecimal("6500"),
        )

    private fun foreign(
        signedQuantity: String = "-1.5",
        source: ForeignPerpPositionSource = ForeignPerpPositionSource.EXCHANGE_API,
        commission: BigDecimal? = BigDecimal("2.5"),
        funding: BigDecimal? = BigDecimal("-0.75"),
        positionRisk: ForeignPerpPositionRisk? = risk(),
        symbolConfig: ForeignPerpSymbolConfig? = ForeignPerpSymbolConfig(10, ForeignPerpMarginType.ISOLATED),
    ) = ForeignPerpAccountSnapshot(
        signedQuantity = BigDecimal(signedQuantity),
        entryPrice = BigDecimal("65000"),
        entryCommissionUsdt = commission,
        fundingUsdt = funding,
        takerFeeRate = BigDecimal("0.0005"),
        askLevels = listOf(PriceLevel(BigDecimal("65000"), BigDecimal("10"))),
        lastPrice = BigDecimal("65000"),
        askObservedAt = at,
        positionSource = source,
        positionRisk = positionRisk,
        symbolConfig = symbolConfig,
    )

    @Test
    fun `거래소 API 두 레그가 있으면 스냅샷 값이 그대로 관측에 담긴다`() {
        val obs = LegObservations.of(korea(), foreign())

        assertThat(obs.korea).isEqualTo(KoreaLegObservation(BigDecimal("1234.5678")))
        assertThat(obs.foreign).isEqualTo(
            ForeignLegObservation(
                leverage = 10,
                marginType = ForeignPerpMarginType.ISOLATED,
                liquidationPrice = BigDecimal("41000.12"),
                markPrice = BigDecimal("65000.5"),
                isolatedMargin = BigDecimal("321.987"),
                maintMargin = BigDecimal("12.25"),
                entryCommissionUsdt = BigDecimal("2.5"),
                fundingUsdt = BigDecimal("-0.75"),
            ),
        )
    }

    @Test
    fun `스냅샷에 없는 값은 0 이 아니라 null 로 남는다`() {
        val obs = LegObservations.of(
            korea(entryFeeKrw = null),
            foreign(commission = null, funding = null, positionRisk = null, symbolConfig = null),
        )

        assertThat(obs.korea).isEqualTo(KoreaLegObservation(null))
        assertThat(obs.foreign).isEqualTo(ForeignLegObservation(null, null, null, null, null, null, null, null))
    }

    @Test
    fun `청산가만 없고 위험 지표가 있으면 청산가만 null 이다`() {
        val foreign = LegObservations.of(korea(), foreign(positionRisk = risk(liquidationPrice = null))).foreign!!

        assertThat(foreign.liquidationPrice).isNull()
        assertThat(foreign.markPrice).isEqualByComparingTo("65000.5")
        assertThat(foreign.isolatedMargin).isEqualByComparingTo("321.987")
        assertThat(foreign.maintMargin).isEqualByComparingTo("12.25")
    }

    @Test
    fun `symbolConfig 와 positionRisk 는 서로 독립적으로 비어 있을 수 있다`() {
        val noConfig = LegObservations.of(korea(), foreign(symbolConfig = null)).foreign!!
        assertThat(noConfig.leverage).isNull()
        assertThat(noConfig.marginType).isNull()
        assertThat(noConfig.markPrice).isNotNull()

        val noRisk = LegObservations.of(korea(), foreign(positionRisk = null)).foreign!!
        assertThat(noRisk.markPrice).isNull()
        assertThat(noRisk.isolatedMargin).isNull()
        assertThat(noRisk.maintMargin).isNull()
        assertThat(noRisk.liquidationPrice).isNull()
        assertThat(noRisk.leverage).isEqualTo(10)
    }

    @Test
    fun `수동 환경변수 포지션이면 바이낸스 레그는 없고 빗썸 레그는 남는다`() {
        val obs = LegObservations.of(korea(), foreign(source = ForeignPerpPositionSource.MANUAL_ENV))

        assertThat(obs.foreign).isNull()
        assertThat(obs.korea).isEqualTo(KoreaLegObservation(BigDecimal("1234.5678")))
    }

    @Test
    fun `바이낸스 수량이 0 이면 scale 과 무관하게 바이낸스 레그가 없다`() {
        assertThat(LegObservations.of(korea(), foreign(signedQuantity = "0")).foreign).isNull()
        assertThat(LegObservations.of(korea(), foreign(signedQuantity = "0.00000000")).foreign).isNull()
        assertThat(LegObservations.of(korea(), foreign(signedQuantity = "-0.000")).foreign).isNull()
    }

    @Test
    fun `빗썸 수량이 0 이면 scale 과 무관하게 빗썸 레그가 없다`() {
        assertThat(LegObservations.of(korea(quantity = "0"), foreign()).korea).isNull()
        assertThat(LegObservations.of(korea(quantity = "0.00000000"), foreign()).korea).isNull()
        assertThat(LegObservations.of(korea(quantity = "0"), foreign()).foreign).isNotNull()
    }

    @Test
    fun `두 레그 모두 수량이 0 이면 NONE 이다`() {
        val obs = LegObservations.of(korea(quantity = "0.0"), foreign(signedQuantity = "0.0"))

        assertThat(obs).isEqualTo(LegObservations.NONE)
        assertThat(LegObservations.NONE.korea).isNull()
        assertThat(LegObservations.NONE.foreign).isNull()
    }

    @Test
    fun `toString 은 값을 노출하지 않는 고정 문자열이다`() {
        val obs = LegObservations.of(korea(), foreign())

        assertThat(obs.toString()).isEqualTo("LegObservations")
        assertThat(obs.korea.toString()).isEqualTo("KoreaLegObservation")
        assertThat(obs.foreign.toString()).isEqualTo("ForeignLegObservation")
        assertThat(LegObservations.NONE.toString()).isEqualTo("LegObservations")
    }

    @Test
    fun `PositionHistoryRound 의 기본 레그 관측은 NONE 이다`() {
        val field = PositionHistoryRound::class.java.getDeclaredField("legObservations")
        assertThat(field.type).isEqualTo(LegObservations::class.java)
        assertThat(LegObservations.NONE).isSameAs(LegObservations.NONE)
    }
}
