package io.premiumspread.domain.accountpnl

import io.premiumspread.domain.accountpnl.FillSide.BUY
import io.premiumspread.domain.accountpnl.FillSide.SELL
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.MathContext
import java.time.Instant

class RealizedPnlPolicyTest {

    private val t0 = Instant.parse("2026-11-10T00:00:00Z")

    private fun at(minutes: Long): Instant = t0.plusSeconds(minutes * 60)

    private fun buy(id: String, qty: String, funds: String, fee: String, minute: Long) =
        KoreaSpotFill(id, BUY, BigDecimal(qty), BigDecimal(funds), BigDecimal(fee), at(minute))

    private fun sell(id: String, qty: String, funds: String, fee: String, minute: Long) =
        KoreaSpotFill(id, SELL, BigDecimal(qty), BigDecimal(funds), BigDecimal(fee), at(minute))

    private fun perp(id: Long, qty: String, pnl: String, commission: String, minute: Long) =
        ForeignPerpFill(id, BigDecimal(qty), BigDecimal(pnl), BigDecimal(commission), at(minute))

    private fun funding(id: Long, income: String, minute: Long) = ForeignFunding(id, BigDecimal(income), at(minute))

    // 매수2 → 매도1 → 매수1 → 매도2
    // b1: 원가 = 200000 + 100 = 200100, 보유 2
    // s1: 매도 원가 = 1 × 200100 / 2 = 100050, 실현 = (110000 − 55) − 100050 = 9895, 남은 원가 100050, 보유 1
    // b2: 원가 = 100050 + 120000 + 60 = 220110, 보유 2
    // s2: 매도 원가 = 2 × 220110 / 2 = 220110, 실현 += (260000 − 130) − 220110 = 39760 → 누적 49655
    private val b1 = buy("b1", "2", "200000", "100", 1)
    private val s1 = sell("s1", "1", "110000", "55", 2)
    private val b2 = buy("b2", "1", "120000", "60", 3)
    private val s2 = sell("s2", "2", "260000", "130", 4)

    @Test
    fun `빗썸 이동평균 원가 - 단계별 실현값이 손계산과 같다`() {
        assertThat(RealizedPnlPolicy.korea(listOf(b1), BigDecimal("2"))!!.realizedKrw)
            .isEqualByComparingTo("0")
        assertThat(RealizedPnlPolicy.korea(listOf(b1, s1), BigDecimal("1"))!!.realizedKrw)
            .isEqualByComparingTo("9895")
        assertThat(RealizedPnlPolicy.korea(listOf(b1, s1, b2), BigDecimal("2"))!!.realizedKrw)
            .isEqualByComparingTo("9895")
        assertThat(RealizedPnlPolicy.korea(listOf(b1, s1, b2, s2), BigDecimal.ZERO)!!.realizedKrw)
            .isEqualByComparingTo("49655")
    }

    @Test
    fun `빗썸 전량 정리 값은 매도 순액 합에서 매수 원가 합을 뺀 값이다`() {
        // Σsell(funds − fee) = 109945 + 259870 = 369815
        // Σbuy(funds + fee) = 200100 + 120060 = 320160
        // 369815 − 320160 = 49655
        val all = listOf(b1, s1, b2, s2)
        val sells = all.filter { it.side == SELL }.sumOf { it.fundsKrw - it.feeKrw }
        val buys = all.filter { it.side == BUY }.sumOf { it.fundsKrw + it.feeKrw }

        assertThat(sells - buys).isEqualByComparingTo("49655")
        assertThat(RealizedPnlPolicy.korea(all, BigDecimal.ZERO)!!.realizedKrw).isEqualByComparingTo(sells - buys)
    }

    @Test
    fun `빗썸 나누어 떨어지지 않는 평균이어도 전량 정리 값은 공식과 정확히 같다`() {
        // 원가 100001 / 보유 3 → 1개당 33333.666... (DECIMAL128 로 나눔)
        // s1: 매도 원가 = 1 × 100001 / 3, s2: 남은 원가 전부 → 합계 100001
        // 실현 = (40000 − 7) + (80000 − 11) − 100001 = 19981
        val fills = listOf(
            buy("x1", "3", "100000", "1", 1),
            sell("x2", "1", "40000", "7", 2),
            sell("x3", "2", "80000", "11", 3),
        )

        assertThat(RealizedPnlPolicy.korea(fills, BigDecimal.ZERO)!!.realizedKrw).isEqualByComparingTo("19981")

        // 부분 정리: 실현 = (40000 − 7) − 1 × 100001 / 3 (DECIMAL128)
        val partialExpected = BigDecimal("39993")
            .subtract(BigDecimal("100001").divide(BigDecimal("3"), MathContext.DECIMAL128))
        assertThat(RealizedPnlPolicy.korea(fills.take(2), BigDecimal("2"))!!.realizedKrw)
            .isEqualByComparingTo(partialExpected)
    }

    @Test
    fun `빗썸 같은 시각 체결은 id 순으로 적용한다`() {
        // 입력은 매도 b 가 먼저지만 (filledAt, id) 순이면 매수 a → 매도 b
        // 원가 100050, 실현 = (110000 − 55) − 100050 = 9895. 입력 순서대로면 보유 0 매도라 null 이다.
        val fills = listOf(
            sell("b", "1", "110000", "55", 1),
            buy("a", "1", "100000", "50", 1),
        )

        assertThat(RealizedPnlPolicy.korea(fills, BigDecimal.ZERO)!!.realizedKrw).isEqualByComparingTo("9895")
    }

    @Test
    fun `빗썸 보유보다 dust 미만 더 팔면 보유 원가 전부를 빼고 확정한다`() {
        // 원가 100050, 보유 1. 매도 1.00005 → 보유 원가 전부 제거, 보유 0
        // 실현 = (110000 − 55) − 100050 = 9895. 수량 1 − 1.00005 = −0.00005 = 기대
        val fills = listOf(
            buy("u1", "1", "100000", "50", 1),
            sell("u2", "1.00005", "110000", "55", 2),
        )

        assertThat(RealizedPnlPolicy.korea(fills, BigDecimal("-0.00005"))!!.realizedKrw).isEqualByComparingTo("9895")
    }

    @Test
    fun `빗썸 체결 시각 순으로 정렬해 계산한다`() {
        assertThat(RealizedPnlPolicy.korea(listOf(s2, b2, s1, b1), BigDecimal.ZERO)!!.realizedKrw)
            .isEqualByComparingTo("49655")
    }

    @Test
    fun `빗썸 같은 id 체결이 두 번 와도 값이 같다`() {
        val duplicated = listOf(b1, s1, s1, b2, b1, s2, s2)

        assertThat(RealizedPnlPolicy.korea(duplicated, BigDecimal.ZERO)!!.realizedKrw)
            .isEqualByComparingTo("49655")
    }

    @Test
    fun `빗썸 수량 차이가 dust 미만이면 확정한다`() {
        // Σbuy − Σsell = 1.00009 − 1 = 0.00009, 기대 0 → |0.00009| < 0.0001
        // 평균 원가 = 100009 / 1.00009 = 100000, 매도 원가 = 1 × 100000 = 100000
        // 실현 = (100000 − 50) − 100000 = −50 (dust 0.00009 의 원가 9 는 보유분으로 남는다)
        val fills = listOf(
            buy("d1", "1.00009", "100009", "0", 1),
            sell("d2", "1", "100000", "50", 2),
        )

        assertThat(RealizedPnlPolicy.korea(fills, BigDecimal.ZERO)!!.realizedKrw).isEqualByComparingTo("-50")
    }

    @Test
    fun `빗썸 수량 차이가 dust 이상이면 null 이다`() {
        // Σbuy − Σsell = 1.0001 − 1 = 0.0001, 기대 0 → |0.0001| >= 0.0001
        val fills = listOf(
            buy("d1", "1.0001", "100010", "0", 1),
            sell("d2", "1", "100000", "50", 2),
        )

        assertThat(RealizedPnlPolicy.korea(fills, BigDecimal.ZERO)).isNull()
    }

    @Test
    fun `빗썸 기대 수량과 다르면 null 이다`() {
        assertThat(RealizedPnlPolicy.korea(listOf(b1, s1), BigDecimal.ZERO)).isNull()
    }

    @Test
    fun `빗썸 보유 0 에서 dust 미만을 팔면 원가 0 으로 매도 순액을 실현하고 보유는 0 이다`() {
        // 앞 건의 dust 잔량을 이 건의 첫 매수보다 먼저 판 경우
        val fills = listOf(
            sell("r1", "0.00005", "5000", "2", 1),
            buy("r2", "1", "100000", "50", 2),
            sell("r3", "1", "110000", "55", 3),
        )

        // (5000 − 2) + (110000 − 55) − (100000 + 50) = 4998 + 9895
        assertThat(RealizedPnlPolicy.korea(fills, BigDecimal.ZERO)!!.realizedKrw).isEqualByComparingTo("14893")
    }

    @Test
    fun `빗썸 보유 0 에서 정확히 dust 를 팔면 null 이다`() {
        val fills = listOf(sell("r1", "0.0001", "10000", "4", 1))

        assertThat(RealizedPnlPolicy.korea(fills, BigDecimal("-0.0001"))).isNull()
    }

    @Test
    fun `빗썸 보유 0 에서 dust 이상 매도하면 null 이다`() {
        val fills = listOf(
            sell("z1", "1", "100000", "50", 1),
            buy("z2", "1", "90000", "45", 2),
        )

        assertThat(RealizedPnlPolicy.korea(fills, BigDecimal.ZERO)).isNull()
    }

    @Test
    fun `빗썸 보유보다 dust 이상 많이 매도하면 null 이다`() {
        val fills = listOf(
            buy("o1", "1", "100000", "50", 1),
            sell("o2", "1.0001", "100010", "50", 2),
        )

        assertThat(RealizedPnlPolicy.korea(fills, BigDecimal("-0.0001"))).isNull()
    }

    @Test
    fun `빗썸 체결이 없고 기대도 0 이면 실현 0 이다`() {
        assertThat(RealizedPnlPolicy.korea(emptyList(), BigDecimal.ZERO)!!.realizedKrw).isEqualByComparingTo("0")
    }

    @Test
    fun `바이낸스 순은 실현 손익에서 수수료 절댓값을 빼고 펀딩을 더한다`() {
        // 실현 = 0 + 12.5 = 12.5
        // 수수료 = |0.7| + |−0.6| = 1.3 (음수 입력도 비용)
        // 펀딩 = −0.3 + 0.1 = −0.2 (id 1 중복은 한 번만)
        // 순 = 12.5 − 1.3 − 0.2 = 11.0
        val fills = listOf(perp(1, "-0.5", "0", "0.7", 1), perp(2, "0.5", "12.5", "-0.6", 2))
        val fundings = listOf(funding(1, "-0.3", 1), funding(2, "0.1", 2), funding(1, "-0.3", 1))

        val r = RealizedPnlPolicy.foreign(fills, fundings, BigDecimal.ZERO)!!

        assertThat(r.realizedPnlUsdt).isEqualByComparingTo("12.5")
        assertThat(r.commissionUsdt).isEqualByComparingTo("1.3")
        assertThat(r.fundingUsdt).isEqualByComparingTo("-0.2")
        assertThat(r.netUsdt).isEqualByComparingTo("11.0")
    }

    @Test
    fun `바이낸스 같은 id 체결이 두 번 와도 값이 같다`() {
        val f1 = perp(1, "-0.5", "0", "0.7", 1)
        val f2 = perp(2, "0.5", "12.5", "0.6", 2)

        val once = RealizedPnlPolicy.foreign(listOf(f1, f2), emptyList(), BigDecimal.ZERO)!!
        val twice = RealizedPnlPolicy.foreign(listOf(f1, f2, f2, f1), emptyList(), BigDecimal.ZERO)!!

        assertThat(twice.netUsdt).isEqualByComparingTo(once.netUsdt)
        assertThat(twice.netUsdt).isEqualByComparingTo("11.2")
    }

    @Test
    fun `바이낸스 부호 수량 합이 기대와 다르면 null 이다`() {
        val fills = listOf(perp(1, "-0.5", "0", "0.7", 1), perp(2, "0.5", "12.5", "0.6", 2))

        assertThat(RealizedPnlPolicy.foreign(fills, emptyList(), BigDecimal("-0.5"))).isNull()
    }

    @Test
    fun `바이낸스 FLAT 기대인데 롱 잔량이 남으면 null 이다`() {
        // −0.5 + 0.6 = +0.1 ≠ 0
        val fills = listOf(perp(1, "-0.5", "0", "0.7", 1), perp(2, "0.6", "12.5", "0.6", 2))

        assertThat(RealizedPnlPolicy.foreign(fills, emptyList(), BigDecimal.ZERO)).isNull()
    }

    @Test
    fun `바이낸스 부분 정리는 남은 숏 수량이 기대와 같으면 확정한다`() {
        // −0.5 + 0.2 = −0.3 (scale 무관 비교)
        val fills = listOf(perp(1, "-0.5", "0", "0.7", 1), perp(2, "0.2", "5", "0.3", 2))

        val r = RealizedPnlPolicy.foreign(fills, emptyList(), BigDecimal("-0.300"))!!

        assertThat(r.netUsdt).isEqualByComparingTo("4.0")
    }

    @Test
    fun `합계는 빗썸 실현과 바이낸스 순 환산의 합을 원 단위 HALF_UP 한다`() {
        val foreign = ForeignRealized(BigDecimal("1"), BigDecimal.ZERO, BigDecimal.ZERO)

        // 1000.2 + 1 × 1400.3 = 2400.5 → 2401
        val up = RealizedPnlPolicy.totalKrw(KoreaRealized(BigDecimal("1000.2")), foreign, BigDecimal("1400.3"))
        // 1000.19 + 1400.3 = 2400.49 → 2400
        val down = RealizedPnlPolicy.totalKrw(KoreaRealized(BigDecimal("1000.19")), foreign, BigDecimal("1400.3"))
        // −3800.8 + 1400.3 = −2400.5 → −2401 (HALF_UP 은 0 에서 먼 쪽)
        val negative = RealizedPnlPolicy.totalKrw(KoreaRealized(BigDecimal("-3800.8")), foreign, BigDecimal("1400.3"))

        assertThat(up).isEqualTo(BigDecimal("2401"))
        assertThat(down).isEqualTo(BigDecimal("2400"))
        assertThat(negative).isEqualTo(BigDecimal("-2401"))
    }

    @Test
    fun `합계는 바이낸스 순에 수수료와 펀딩을 반영해 환산한다`() {
        // 순 = 10 − 0.5 + (−0.25) = 9.25, × 1400 = 12950, + 49655 = 62605
        val foreign = ForeignRealized(BigDecimal("10"), BigDecimal("0.5"), BigDecimal("-0.25"))

        val total = RealizedPnlPolicy.totalKrw(KoreaRealized(BigDecimal("49655")), foreign, BigDecimal("1400"))

        assertThat(total).isEqualTo(BigDecimal("62605"))
    }

    @Test
    fun `값 타입 toString 에 금액과 수량이 없다`() {
        val money = "987654321"
        val legs = PositionLegs(
            KoreaLeg.of(BigDecimal(money), BigDecimal(money)),
            ForeignLeg.of(BigDecimal("-$money"), BigDecimal(money)),
        )
        val target = SettlementTarget(7, 3, t0, legs, ForeignPerpPositionSource.EXCHANGE_API, t0, 2)
        val korea = KoreaRealized(BigDecimal(money))
        val foreign = ForeignRealized(BigDecimal(money), BigDecimal(money), BigDecimal(money))
        val values = listOf(
            KoreaSpotFill("k", BUY, BigDecimal(money), BigDecimal(money), BigDecimal(money), t0),
            ForeignPerpFill(1, BigDecimal(money), BigDecimal(money), BigDecimal(money), t0),
            ForeignFunding(1, BigDecimal(money), t0),
            korea,
            foreign,
            AppliedFx(BigDecimal(money), t0),
            target,
            SettlementOutcome(
                target,
                SettlementStatus.CONFIRMED,
                korea,
                SettlementStatus.CONFIRMED,
                foreign,
                AppliedFx(BigDecimal(money), t0),
                BigDecimal(money),
                null,
                t0,
            ),
        )

        values.forEach { assertThat(it.toString()).doesNotContain(money) }
        assertThat(korea.toString()).doesNotContainPattern("[0-9]")
        assertThat(foreign.toString()).doesNotContainPattern("[0-9]")
        assertThat(target.toString()).isEqualTo("SettlementTarget(episodeId=7, seq=3, occurredAt=$t0)")
    }

    @Test
    fun `정산 완료는 빗썸 확정이고 바이낸스가 확정 또는 수동일 때다`() {
        val target = SettlementTarget(
            1,
            1,
            t0,
            PositionLegs(KoreaLeg.EMPTY, ForeignLeg.FLAT),
            ForeignPerpPositionSource.EXCHANGE_API,
            t0,
            0,
        )
        fun outcome(k: SettlementStatus, f: SettlementStatus) =
            SettlementOutcome(target, k, null, f, null, null, null, null, t0)

        assertThat(outcome(SettlementStatus.CONFIRMED, SettlementStatus.CONFIRMED).settled).isTrue()
        assertThat(outcome(SettlementStatus.CONFIRMED, SettlementStatus.MANUAL).settled).isTrue()
        assertThat(outcome(SettlementStatus.CONFIRMED, SettlementStatus.PENDING).settled).isFalse()
        assertThat(outcome(SettlementStatus.PENDING, SettlementStatus.CONFIRMED).settled).isFalse()
        assertThat(outcome(SettlementStatus.MANUAL, SettlementStatus.MANUAL).settled).isFalse()
    }
}
