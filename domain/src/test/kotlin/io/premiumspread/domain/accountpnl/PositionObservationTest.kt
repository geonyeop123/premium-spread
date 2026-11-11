package io.premiumspread.domain.accountpnl

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

class PositionObservationTest {

    private val at = Instant.parse("2026-11-10T00:00:00Z")
    private val fx = BigDecimal("1400")

    private fun level(price: String, quantity: String) = PriceLevel(BigDecimal(price), BigDecimal(quantity))

    private fun pnl(): AccountNetPnl {
        val korea = KoreaSpotAccountSnapshot(
            BigDecimal("0.266"),
            BigDecimal("113016278"),
            false,
            BigDecimal("12000"),
            BigDecimal("0.0004"),
            listOf(level("109500000", "1.0")),
            BigDecimal("109575000"),
            at,
        )
        val foreign = ForeignPerpAccountSnapshot(
            BigDecimal("-0.266"),
            BigDecimal("83549.4"),
            BigDecimal("8.9"),
            BigDecimal("3.2"),
            BigDecimal("0.0005"),
            listOf(level("81580", "5.0")),
            BigDecimal("81577"),
            at,
        )
        return AccountNetPnl.calculate(AccountHedgePosition(korea, foreign), BigDecimal("1328.5"), at, at)
    }

    private val reference = ReferenceLegs(
        BigDecimal("0.3"),
        BigDecimal("100000000"),
        BigDecimal("0.3"),
        BigDecimal("70000"),
    )

    private fun market(fxRate: BigDecimal = fx, korea: List<PriceLevel> = defaultKorea, foreign: List<PriceLevel> = defaultForeign) =
        MarketQuote(korea, foreign, fxRate)

    private val defaultKorea = listOf(level("100000000", "0.1"), level("99999000", "0.2"), level("1", "100"))
    private val defaultForeign = listOf(level("70000", "0.1"), level("70001", "0.2"), level("99999", "100"))

    @Test
    fun `holding 은 AccountPnlBreakdown 의 김프 값과 같고 netKrw 는 scale 0 이다`() {
        val pnl = pnl()
        val breakdown = AccountPnlBreakdown.of(pnl)

        val o = PositionObservation.holding(pnl)

        assertThat(o.kind).isEqualTo(PositionObservationKind.HOLDING)
        assertThat(o.observedAt).isEqualTo(pnl.calculatedAt)
        assertThat(o.currentPremiumPercent).isEqualTo(breakdown.currentPremiumPercent)
        assertThat(o.entryPremiumPercent).isEqualTo(breakdown.entryPremiumPercent)
        assertThat(o.premiumGapPercentPoint).isEqualTo(breakdown.premiumGapPercentPoint)
        assertThat(o.netPercent).isEqualTo(pnl.netPercent)
        assertThat(o.netKrw!!.scale()).isEqualTo(0)
        assertThat(o.netKrw).isEqualTo(AccountPnlLatest.computed(pnl).netKrw)
        assertThat(o.fxRate).isEqualTo(pnl.fxRate)
        assertThat(o.koreaExitPrice).isEqualTo(pnl.koreaExitPrice)
        assertThat(o.foreignExitPrice).isEqualTo(pnl.foreignExitPrice)
    }

    @Test
    fun `flat 은 reference 수량으로 여러 호가 레벨을 소화한 VWAP 가 청산가다`() {
        val o = PositionObservation.flat(reference, market(), at)!!

        // 0.3 = 0.1 + 0.2 : (100000000×0.1 + 99999000×0.2) / 0.3, (70000×0.1 + 70001×0.2) / 0.3
        assertThat(o.koreaExitPrice).isEqualByComparingTo(BigDecimal("99999333.33333333333333333333333333"))
        assertThat(o.foreignExitPrice).isEqualByComparingTo(BigDecimal("70000.66666666666666666666666666667"))
        assertThat(o.kind).isEqualTo(PositionObservationKind.FLAT)
        assertThat(o.observedAt).isEqualTo(at)
        assertThat(o.fxRate).isEqualTo(fx)
        assertThat(o.netPercent).isNull()
        assertThat(o.netKrw).isNull()
    }

    @Test
    fun `flat 의 김프는 HedgeQuote 와 같은 식이고 매수김프는 reference 평단과 현재 환율로 계산한다`() {
        val o = PositionObservation.flat(reference, market(), at)!!

        assertThat(o.currentPremiumPercent)
            .isEqualTo(HedgeQuote.premiumPercent(o.koreaExitPrice, o.foreignExitPrice, fx))
        assertThat(o.entryPremiumPercent)
            .isEqualTo(HedgeQuote.premiumPercent(BigDecimal("100000000"), BigDecimal("70000"), fx))
        assertThat(o.premiumGapPercentPoint).isEqualTo(o.currentPremiumPercent.subtract(o.entryPremiumPercent))
    }

    @Test
    fun `환율만 바뀌면 현재 김프와 매수김프가 같이 움직여 차이는 0_01 이내로 유지된다`() {
        val a = PositionObservation.flat(reference, market(BigDecimal("1400")), at)!!
        val b = PositionObservation.flat(reference, market(BigDecimal("1420")), at)!!

        assertThat(b.currentPremiumPercent).isNotEqualByComparingTo(a.currentPremiumPercent)
        assertThat(b.entryPremiumPercent).isNotEqualByComparingTo(a.entryPremiumPercent)
        assertThat(b.premiumGapPercentPoint.subtract(a.premiumGapPercentPoint).abs())
            .isLessThanOrEqualTo(BigDecimal("0.01"))
    }

    @Test
    fun `호가 깊이가 부족하면 flat 은 null 이다`() {
        assertThat(PositionObservation.flat(reference, market(korea = listOf(level("100000000", "0.1"))), at)).isNull()
        assertThat(PositionObservation.flat(reference, market(foreign = listOf(level("70000", "0.1"))), at)).isNull()
    }

    @Test
    fun `HOLDING 은 net 값이 있어야 하고 FLAT 은 없어야 한다`() {
        val ok = PositionObservation.holding(pnl())

        assertThatThrownBy { ok.copy(netPercent = null, netKrw = null) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { ok.copy(kind = PositionObservationKind.FLAT) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `민감 값은 toString 에 실리지 않는다`() {
        assertThat(PositionObservation.holding(pnl()).toString())
            .isEqualTo("PositionObservation(kind=HOLDING, observedAt=$at)")
        assertThat(market().toString()).isEqualTo("MarketQuote")
    }

    @Test
    fun `reference 평단이 0 이면 flat 은 예외 없이 null 이다`() {
        val zero = reference.copy(koreaAveragePrice = BigDecimal.ZERO)

        assertThat(PositionObservation.flat(zero, market(), at)).isNull()
    }

    @Test
    fun `평단이 0 인 pnl 이면 holdingOrNull 은 예외 없이 null 이다`() {
        val base = pnl()
        val zeroAvg = base.copy(position = base.position.copy(korea = base.position.korea.copy(averageBuyPrice = BigDecimal.ZERO)))

        assertThat(PositionObservation.holdingOrNull(zeroAvg)).isNull()
        assertThat(PositionObservation.holdingOrNull(base)).isEqualTo(PositionObservation.holding(base))
    }
}
