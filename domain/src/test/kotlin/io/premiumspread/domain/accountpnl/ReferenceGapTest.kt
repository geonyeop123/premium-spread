package io.premiumspread.domain.accountpnl

import io.premiumspread.domain.accountpnl.ReferencePhase.FLAT
import io.premiumspread.domain.accountpnl.ReferencePhase.HOLDING
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

class ReferenceGapTest {

    private val at = Instant.parse("2026-11-10T00:00:00Z")
    private val width = BigDecimal("0.50")
    private val source = ForeignPerpPositionSource.EXCHANGE_API

    private fun level(price: String, quantity: String) = PriceLevel(BigDecimal(price), BigDecimal(quantity))

    private val reference = ReferenceLegs(BigDecimal("0.3"), BigDecimal("100000000"), BigDecimal("0.3"), BigDecimal("70000"))
    private val heldState = ReferenceStepState(reference, HOLDING, 0)
    private val flatLegs = PositionLegs(KoreaLeg.EMPTY, ForeignLeg.FLAT)

    private fun market(fx: String = "1300", foreignExit: String = "70000") =
        MarketQuote(listOf(level("100000000", "1.0")), listOf(level(foreignExit, "1.0")), BigDecimal(fx))

    private fun flatRound(market: MarketQuote = market(), legs: PositionLegs = flatLegs) =
        PositionHistoryRound(legs, source, null, market, at)

    private fun holdingPnl(): AccountNetPnl {
        val korea = KoreaSpotAccountSnapshot(
            BigDecimal("0.266"), BigDecimal("113016278"), false, BigDecimal("12000"), BigDecimal("0.0004"),
            listOf(level("109500000", "1.0")), BigDecimal("109575000"), at,
        )
        val foreign = ForeignPerpAccountSnapshot(
            BigDecimal("-0.266"), BigDecimal("83549.4"), BigDecimal("8.9"), BigDecimal("3.2"), BigDecimal("0.0005"),
            listOf(level("81580", "5.0")), BigDecimal("81577"), at,
        )
        return AccountNetPnl.calculate(AccountHedgePosition(korea, foreign), BigDecimal("1328.5"), at, at)
    }

    private fun holdingRound(): PositionHistoryRound {
        val pnl = holdingPnl()
        val legs = PositionLegs(
            KoreaLeg.of(pnl.position.korea.quantity, pnl.position.korea.averageBuyPrice),
            ForeignLeg.of(pnl.position.foreign.signedQuantity, pnl.position.foreign.entryPrice),
        )
        return PositionHistoryRound(legs, source, pnl, market(), at)
    }

    @Test
    fun `pnl 이 있으면 HOLDING 이고 기준은 현재 포지션 leg 이며 foreign 수량은 절댓값이다`() {
        val round = holdingRound()

        val gap = ReferenceGap.of(round, ReferenceStepState.INITIAL, null)!!

        assertThat(gap.phase).isEqualTo(HOLDING)
        assertThat(gap.reference).isEqualTo(
            ReferenceLegs(BigDecimal("0.266"), BigDecimal("113016278"), BigDecimal("0.266"), BigDecimal("83549.4")),
        )
        assertThat(gap.observation).isEqualTo(PositionObservation.holding(round.pnl!!))
        assertThat(gap.value).isEqualTo(gap.observation.premiumGapPercentPoint)
    }

    @Test
    fun `완전 flat 이고 state 에 reference 가 있으면 FLAT 이며 reference 는 state 것이다`() {
        val gap = ReferenceGap.of(flatRound(), heldState, null)!!

        assertThat(gap.phase).isEqualTo(FLAT)
        assertThat(gap.reference).isEqualTo(reference)
        assertThat(gap.observation.kind).isEqualTo(PositionObservationKind.FLAT)
        assertThat(gap.observation.observedAt).isEqualTo(at)
    }

    @Test
    fun `state 에 reference 가 없으면 부트스트랩 reference 로 FLAT 판정한다`() {
        val gap = ReferenceGap.of(flatRound(), ReferenceStepState.INITIAL, reference)!!

        assertThat(gap.phase).isEqualTo(FLAT)
        assertThat(gap.reference).isEqualTo(reference)
    }

    @Test
    fun `state 의 reference 가 있으면 부트스트랩은 쓰지 않는다`() {
        val other = reference.copy(koreaAveragePrice = BigDecimal("90000000"))

        val gap = ReferenceGap.of(flatRound(), heldState, other)!!

        assertThat(gap.reference).isEqualTo(reference)
    }

    @Test
    fun `바이낸스 롱은 flat 으로 정규화되어 FLAT 이다`() {
        val long = PositionLegs(KoreaLeg.EMPTY, ForeignLeg.of(BigDecimal("0.3"), BigDecimal("70000")))

        assertThat(ReferenceGap.of(flatRound(legs = long), heldState, null)!!.phase).isEqualTo(FLAT)
    }

    @Test
    fun `한쪽 leg 만 있으면 null 이다`() {
        val koreaOnly = PositionLegs(KoreaLeg.of(BigDecimal("0.3"), BigDecimal("100000000")), ForeignLeg.FLAT)
        val foreignOnly = PositionLegs(KoreaLeg.EMPTY, ForeignLeg.of(BigDecimal("-0.3"), BigDecimal("70000")))

        assertThat(ReferenceGap.of(flatRound(legs = koreaOnly), heldState, reference)).isNull()
        assertThat(ReferenceGap.of(flatRound(legs = foreignOnly), heldState, reference)).isNull()
    }

    @Test
    fun `기준이 없으면 null 이다`() {
        assertThat(ReferenceGap.of(flatRound(), ReferenceStepState.INITIAL, null)).isNull()
    }

    @Test
    fun `관측 계산이 불가능하면 null 이다 - 호가 깊이 부족`() {
        val shallow = MarketQuote(listOf(level("100000000", "0.1")), listOf(level("70000", "1.0")), BigDecimal("1300"))

        assertThat(ReferenceGap.of(flatRound(shallow), heldState, null)).isNull()
    }

    @Test
    fun `needsBootstrap 은 완전 flat 이고 reference 가 없을 때만 true 다`() {
        val koreaOnly = PositionLegs(KoreaLeg.of(BigDecimal("0.3"), BigDecimal("100000000")), ForeignLeg.FLAT)

        assertThat(ReferenceGap.needsBootstrap(flatRound(), ReferenceStepState.INITIAL)).isTrue()
        assertThat(ReferenceGap.needsBootstrap(flatRound(), heldState)).isFalse()
        assertThat(ReferenceGap.needsBootstrap(flatRound(legs = koreaOnly), ReferenceStepState.INITIAL)).isFalse()
        assertThat(ReferenceGap.needsBootstrap(holdingRound(), ReferenceStepState.INITIAL)).isFalse()
    }

    // AC2 — 실시간 환율 기준 판정. 매도 호가만 고정한 FLAT 에서 환율 1300 → 1400.

    private fun stepAcrossFx(foreignExit: String): List<AccountAlert.ReferenceStep> {
        val first = ReferenceGap.of(flatRound(market("1300", foreignExit)), heldState, null)
        val a = ReferenceStepPolicy.decide(heldState, first, width)
        val second = ReferenceGap.of(flatRound(market("1400", foreignExit)), a.next, null)
        val b = ReferenceStepPolicy.decide(a.next, second, width)
        return listOfNotNull(a.alert, b.alert)
    }

    @Test
    fun `환율 1300 에서 1400 으로 바뀌어도 gap 0 이면 알림 0건`() {
        assertThat(stepAcrossFx("70000")).isEmpty()
    }

    @Test
    fun `gap 0_30 은 환율이 바뀌어도 0_50 에 못 미쳐 알림 0건`() {
        assertThat(stepAcrossFx("69769")).isEmpty()
    }

    @Test
    fun `gap 0_49 가 환율 1400 에서 0_50 이상이 되면 상승 알림 정확히 1건`() {
        val alerts = stepAcrossFx("69623")

        assertThat(alerts).hasSize(1)
        assertThat(alerts.single().direction).isEqualTo(SwingDirection.UP)
        assertThat(alerts.single().level).isEqualTo(1)
        assertThat(alerts.single().observation.premiumGapPercentPoint).isGreaterThanOrEqualTo(width)
        assertThat(alerts.single().observation.fxRate).isEqualByComparingTo("1400")
    }
}
