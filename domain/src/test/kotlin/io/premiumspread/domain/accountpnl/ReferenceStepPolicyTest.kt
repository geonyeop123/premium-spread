package io.premiumspread.domain.accountpnl

import io.premiumspread.domain.accountpnl.ReferencePhase.FLAT
import io.premiumspread.domain.accountpnl.ReferencePhase.HOLDING
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

class ReferenceStepPolicyTest {

    private val width = BigDecimal("0.50")
    private val at = Instant.parse("2026-11-10T00:00:00Z")

    private fun ref(koreaAvg: String = "100000000", foreignEntry: String = "70000", quantity: String = "0.3") =
        ReferenceLegs(BigDecimal(quantity), BigDecimal(koreaAvg), BigDecimal(quantity), BigDecimal(foreignEntry))

    private fun gap(value: String, phase: ReferencePhase = HOLDING, reference: ReferenceLegs = ref()): ReferenceGap {
        val kind = if (phase == HOLDING) PositionObservationKind.HOLDING else PositionObservationKind.FLAT
        val net = if (phase == HOLDING) BigDecimal("1.00") else null
        val observation = PositionObservation(
            kind, at, net, net, BigDecimal("3.00"), BigDecimal("2.00"), BigDecimal(value),
            BigDecimal("1400"), BigDecimal("100000000"), BigDecimal("70000"),
        )
        return ReferenceGap(phase, reference, observation)
    }

    private fun state(level: Int, phase: ReferencePhase = HOLDING, reference: ReferenceLegs = ref()) =
        ReferenceStepState(reference, phase, level)

    private fun step(state: ReferenceStepState, value: String, phase: ReferencePhase = HOLDING, reference: ReferenceLegs = ref()) =
        ReferenceStepPolicy.decide(state, gap(value, phase, reference), width)

    @Test
    fun `상태가 없으면 기준만 잡고 알림이 없다 - 0_10 이면 level 0`() {
        val d = step(ReferenceStepState.INITIAL, "0.10")

        assertThat(d.alert).isNull()
        assertThat(d.next).isEqualTo(state(0))
    }

    @Test
    fun `상태가 없을 때 큰 값도 알림 없이 trunc 단계로 초기화한다`() {
        val d = step(ReferenceStepState.INITIAL, "1.60")

        assertThat(d.alert).isNull()
        assertThat(d.next.level).isEqualTo(3)
    }

    @Test
    fun `음수 초기값은 0 쪽으로 자른다 - -0_70 은 level -1`() {
        val d = step(ReferenceStepState.INITIAL, "-0.70")

        assertThat(d.alert).isNull()
        assertThat(d.next.level).isEqualTo(-1)
    }

    @Test
    fun `0_50 경계에 닿으면 상승 알림 1건 - 경계 포함`() {
        val d = step(state(0), "0.50")

        assertThat(d.alert).isNotNull
        assertThat(d.alert!!.direction).isEqualTo(SwingDirection.UP)
        assertThat(d.alert!!.level).isEqualTo(1)
        assertThat(d.alert!!.boundary).isEqualByComparingTo("0.50")
        assertThat(d.next.level).isEqualTo(1)
    }

    @Test
    fun `1_00 경계에서도 상승 알림 1건`() {
        val d = step(state(1), "1.00")

        assertThat(d.alert!!.direction).isEqualTo(SwingDirection.UP)
        assertThat(d.alert!!.level).isEqualTo(2)
        assertThat(d.alert!!.boundary).isEqualByComparingTo("1.00")
    }

    @Test
    fun `경계 바로 아래는 알리지 않는다`() {
        val d = step(state(0), "0.49")

        assertThat(d.alert).isNull()
        assertThat(d.next.level).isEqualTo(0)
    }

    @Test
    fun `0_49 와 0_51 사이를 진동해도 첫 통과 한 번만 알린다`() {
        var s = state(0)
        val alerts = mutableListOf<AccountAlert.ReferenceStep>()
        listOf("0.49", "0.51", "0.49", "0.51", "0.49", "0.51").forEach {
            val d = step(s, it)
            d.alert?.let(alerts::add)
            s = d.next
        }

        assertThat(alerts).hasSize(1)
        assertThat(alerts.single().level).isEqualTo(1)
    }

    @Test
    fun `level 1 에서 -0_10 으로 내려가면 0 경계 하락 알림 1건`() {
        val d = step(state(1), "-0.10")

        assertThat(d.alert!!.direction).isEqualTo(SwingDirection.DOWN)
        assertThat(d.alert!!.level).isEqualTo(0)
        assertThat(d.alert!!.boundary).isEqualByComparingTo("0")
        assertThat(d.next.level).isEqualTo(0)
    }

    @Test
    fun `0_10 에서 시작해 1_60 으로 한 번에 뛰면 알림은 1건이고 level 은 3`() {
        val init = step(ReferenceStepState.INITIAL, "0.10")
        val d = step(init.next, "1.60")

        assertThat(d.alert!!.direction).isEqualTo(SwingDirection.UP)
        assertThat(d.alert!!.level).isEqualTo(3)
        assertThat(d.alert!!.boundary).isEqualByComparingTo("1.50")
        assertThat(d.next.level).isEqualTo(3)
    }

    @Test
    fun `한 번에 크게 떨어지면 ceil 단계로 하락 알림 1건`() {
        val d = step(state(2), "-0.60")

        assertThat(d.alert!!.direction).isEqualTo(SwingDirection.DOWN)
        assertThat(d.alert!!.level).isEqualTo(-1)
    }

    @Test
    fun `gap 이 null 이면 상태를 그대로 두고 알림이 없다`() {
        val s = state(2)

        val d = ReferenceStepPolicy.decide(s, null, width)

        assertThat(d.next).isSameAs(s)
        assertThat(d.alert).isNull()
    }

    @Test
    fun `보유 중 평단이 바뀌면 알림 없이 새 기준으로 trunc 초기화한다`() {
        val changedKorea = ref(koreaAvg = "101000000")
        val d = step(state(0), "1.20", HOLDING, changedKorea)

        assertThat(d.alert).isNull()
        assertThat(d.next.reference).isEqualTo(changedKorea)
        assertThat(d.next.level).isEqualTo(2)
    }

    @Test
    fun `바이낸스 진입가만 바뀌어도 전환이다`() {
        val d = step(state(0), "1.20", HOLDING, ref(foreignEntry = "71000"))

        assertThat(d.alert).isNull()
        assertThat(d.next.level).isEqualTo(2)
    }

    @Test
    fun `평단 scale 만 다르면 전환이 아니다 - compareTo`() {
        val d = step(state(0), "0.60", HOLDING, ref(koreaAvg = "100000000.00", foreignEntry = "70000.0"))

        assertThat(d.alert!!.level).isEqualTo(1)
    }

    @Test
    fun `수량만 바뀌면 단계를 이어간다`() {
        val d = step(state(0), "0.60", HOLDING, ref(quantity = "0.5"))

        assertThat(d.alert!!.direction).isEqualTo(SwingDirection.UP)
        assertThat(d.next.reference).isEqualTo(ref(quantity = "0.5"))
    }

    @Test
    fun `FLAT 에서 HOLDING 으로 같은 평단이어도 전환이라 알림 없이 초기화한다`() {
        val d = step(state(0, FLAT), "1.20", HOLDING)

        assertThat(d.alert).isNull()
        assertThat(d.next.phase).isEqualTo(HOLDING)
        assertThat(d.next.level).isEqualTo(2)
    }

    @Test
    fun `HOLDING 에서 FLAT 으로 가면 level 을 이어가고 알림은 FLAT 국면으로 나온다`() {
        val d = step(state(0, HOLDING), "0.55", FLAT)

        assertThat(d.alert!!.phase).isEqualTo(FLAT)
        assertThat(d.alert!!.level).isEqualTo(1)
        assertThat(d.next.phase).isEqualTo(FLAT)
    }

    @Test
    fun `FLAT 이 이어지는 동안 같은 경계는 다시 알리지 않는다`() {
        val first = step(state(0, FLAT), "0.55", FLAT)
        val second = step(first.next, "0.55", FLAT)

        assertThat(first.alert).isNotNull
        assertThat(second.alert).isNull()
    }

    @Test
    fun `상태의 toString 은 phase 와 level 만 담는다`() {
        assertThat(state(2).toString()).isEqualTo("ReferenceStepState(phase=HOLDING, level=2)")
    }

    @Test
    fun `상태는 셋 다 null 이거나 셋 다 값이 있어야 한다`() {
        assertThatThrownBy { ReferenceStepState(ref(), null, 1) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { ReferenceStepState(null, HOLDING, null) }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
