package io.premiumspread.domain.accountpnl

import io.premiumspread.domain.accountpnl.ForeignPerpPositionSource.EXCHANGE_API
import io.premiumspread.domain.accountpnl.ForeignPerpPositionSource.MANUAL_ENV
import io.premiumspread.domain.accountpnl.PositionEventType.ADJUSTED
import io.premiumspread.domain.accountpnl.PositionEventType.CLOSED
import io.premiumspread.domain.accountpnl.PositionEventType.INCREASED
import io.premiumspread.domain.accountpnl.PositionEventType.OPENED
import io.premiumspread.domain.accountpnl.PositionEventType.REDUCED
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

class PositionEpisodePolicyTest {

    private val at = Instant.parse("2026-11-10T00:00:00Z")

    private fun korea(qty: String, avg: String = "100000000") = KoreaLeg.of(BigDecimal(qty), BigDecimal(avg))
    private fun short(qty: String, entry: String = "70000") = ForeignLeg.of(BigDecimal("-$qty"), BigDecimal(entry))
    private fun long(qty: String, entry: String = "70000") = ForeignLeg.of(BigDecimal(qty), BigDecimal(entry))
    private fun legs(k: KoreaLeg, f: ForeignLeg) = PositionLegs(k, f)
    private fun both(kq: String, fq: String = kq, kAvg: String = "100000000", fEntry: String = "70000") =
        legs(korea(kq, kAvg), short(fq, fEntry))

    private fun ref(kq: String, fq: String = kq) =
        // KoreaLeg.of/ForeignLeg.of 가 stripTrailingZeros 한 값과 같은 표현으로 비교한다
        ReferenceLegs(
            BigDecimal(kq).stripTrailingZeros(),
            BigDecimal("100000000").stripTrailingZeros(),
            BigDecimal(fq).stripTrailingZeros(),
            BigDecimal("70000").stripTrailingZeros(),
        )

    private fun episode(l: PositionLegs, reference: ReferenceLegs? = ref("0.3")) = OpenEpisode(7, l, reference, 1)

    private fun decide(open: OpenEpisode?, l: PositionLegs, source: ForeignPerpPositionSource = EXCHANGE_API) =
        PositionEpisodePolicy.decide(open, l, source, at)

    @Test
    fun `열린 건이 없고 양쪽 leg 가 있으면 OPENED 이고 reference 가 그 leg 다`() {
        val d = decide(null, both("0.3"))

        assertThat(d.event!!.type).isEqualTo(OPENED)
        assertThat(d.event!!.hedged).isTrue()
        assertThat(d.event!!.occurredAt).isEqualTo(at)
        assertThat(d.reference).isEqualTo(ref("0.3"))
        assertThat(d.closes).isFalse()
    }

    @Test
    fun `빗썸만 먼저 잡으면 OPENED 미헤지이고 다음 회차 양쪽이면 INCREASED 로 reference 가 채워진다`() {
        val first = decide(null, legs(korea("0.3"), ForeignLeg.FLAT))
        assertThat(first.event!!.type).isEqualTo(OPENED)
        assertThat(first.event!!.hedged).isFalse()
        assertThat(first.reference).isNull()

        val second = decide(episode(legs(korea("0.3"), ForeignLeg.FLAT), null), both("0.3"))
        assertThat(second.event!!.type).isEqualTo(INCREASED)
        assertThat(second.event!!.hedged).isTrue()
        assertThat(second.reference).isEqualTo(ref("0.3"))
    }

    @Test
    fun `바이낸스 숏만 있으면 OPENED 이고 롱만 있으면 사건이 없다`() {
        val shortOnly = decide(null, legs(KoreaLeg.EMPTY, short("0.3")))
        assertThat(shortOnly.event!!.type).isEqualTo(OPENED)
        assertThat(shortOnly.event!!.hedged).isFalse()
        assertThat(shortOnly.reference).isNull()

        val longOnly = decide(null, legs(KoreaLeg.EMPTY, long("0.3")))
        assertThat(longOnly.event).isNull()
        assertThat(longOnly.reference).isNull()
        assertThat(longOnly.closes).isFalse()
    }

    @Test
    fun `포지션도 사건도 없으면 사건이 없다`() {
        val d = decide(null, legs(KoreaLeg.EMPTY, ForeignLeg.FLAT))

        assertThat(d.event).isNull()
        assertThat(d.closes).isFalse()
    }

    @Test
    fun `양쪽 수량이 늘면 INCREASED 줄면 REDUCED 이다`() {
        val before = episode(both("0.3"))

        val up = decide(before, both("0.5"))
        assertThat(up.event!!.type).isEqualTo(INCREASED)
        assertThat(up.event!!.legs).isEqualTo(both("0.5"))
        assertThat(up.reference).isEqualTo(ref("0.5"))

        val down = decide(before, both("0.1"))
        assertThat(down.event!!.type).isEqualTo(REDUCED)
        assertThat(down.reference).isEqualTo(ref("0.1"))
    }

    @Test
    fun `한쪽만 늘어도 INCREASED 이고 한쪽만 줄어도 REDUCED 이다`() {
        val before = episode(both("0.3"))

        assertThat(decide(before, both("0.4", "0.3")).event!!.type).isEqualTo(INCREASED)
        assertThat(decide(before, both("0.3", "0.2")).event!!.type).isEqualTo(REDUCED)
    }

    @Test
    fun `한쪽은 늘고 한쪽은 줄면 ADJUSTED 이다`() {
        val d = decide(episode(both("0.3")), both("0.4", "0.2"))

        assertThat(d.event!!.type).isEqualTo(ADJUSTED)
    }

    @Test
    fun `수량은 같고 평단만 바뀌면 ADJUSTED 이다`() {
        val d = decide(episode(both("0.3")), both("0.3", kAvg = "101000000"))

        assertThat(d.event!!.type).isEqualTo(ADJUSTED)
    }

    @Test
    fun `양쪽이 모두 비면 CLOSED 이고 leg 는 비어 있으며 reference 는 직전 값이다`() {
        val d = decide(episode(both("0.3")), legs(KoreaLeg.EMPTY, ForeignLeg.FLAT))

        assertThat(d.event!!.type).isEqualTo(CLOSED)
        assertThat(d.event!!.legs).isEqualTo(PositionLegs(KoreaLeg.EMPTY, ForeignLeg.FLAT))
        assertThat(d.event!!.hedged).isFalse()
        assertThat(d.closes).isTrue()
        assertThat(d.reference).isEqualTo(ref("0.3"))
    }

    @Test
    fun `빗썸이 비고 바이낸스가 롱이면 롱을 0 으로 정규화해 CLOSED 이다`() {
        val d = decide(episode(both("0.3")), legs(KoreaLeg.EMPTY, long("0.2")))

        assertThat(d.event!!.type).isEqualTo(CLOSED)
        assertThat(d.event!!.legs.foreign).isEqualTo(ForeignLeg.FLAT)
        assertThat(d.closes).isTrue()
    }

    @Test
    fun `빗썸이 비어도 바이낸스 숏이 남으면 REDUCED 이고 닫히지 않는다`() {
        val d = decide(episode(both("0.3")), legs(KoreaLeg.EMPTY, short("0.3")))

        assertThat(d.event!!.type).isEqualTo(REDUCED)
        assertThat(d.event!!.hedged).isFalse()
        assertThat(d.closes).isFalse()
        assertThat(d.reference).isEqualTo(ref("0.3"))
    }

    @Test
    fun `leg 가 같으면 scale 이 달라도 사건이 없고 reference 는 직전 값이다`() {
        val before = episode(both("0.3"))
        val d = decide(before, legs(KoreaLeg(BigDecimal("0.30"), BigDecimal("100000000.0")), short("0.3")))

        assertThat(d.event).isNull()
        assertThat(d.reference).isEqualTo(before.reference)
        assertThat(d.closes).isFalse()
    }

    @Test
    fun `헤지가 아닌 변경은 직전 reference 를 유지한다`() {
        val d = decide(episode(both("0.3")), legs(korea("0.3"), ForeignLeg.FLAT))

        assertThat(d.event!!.type).isEqualTo(REDUCED)
        assertThat(d.reference).isEqualTo(ref("0.3"))
    }

    @Test
    fun `출처가 사건에 실린다`() {
        val d = decide(null, both("0.3"), MANUAL_ENV)

        assertThat(d.event!!.source).isEqualTo(MANUAL_ENV)
    }

    @Test
    fun `normalize 는 롱만 FLAT 으로 바꾼다`() {
        assertThat(PositionEpisodePolicy.normalize(legs(korea("0.3"), long("0.2"))).foreign).isEqualTo(ForeignLeg.FLAT)
        assertThat(PositionEpisodePolicy.normalize(both("0.3"))).isEqualTo(both("0.3"))
    }

    @Test
    fun `민감 값은 toString 에 실리지 않는다`() {
        assertThat(ref("0.3").toString()).isEqualTo("ReferenceLegs")
        assertThat(episode(both("0.3")).toString()).isEqualTo("OpenEpisode(id=7, eventCount=1)")
    }
}
