package io.premiumspread.domain.accountpnl

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

class PnlAlertPolicyTest {

    private val t0 = Instant.parse("2026-10-20T00:00:00Z")

    private val korea = KoreaSpotAccountSnapshot(
        quantity = BigDecimal("0.266"),
        averageBuyPrice = BigDecimal("113016278"),
        averageBuyPriceModified = false,
        entryFeeKrw = BigDecimal("12000"),
        sellFeeRate = BigDecimal("0.0004"),
        bidLevels = listOf(PriceLevel(BigDecimal("109500000"), BigDecimal("1.0"))),
        lastPrice = BigDecimal("109575000"),
        bidObservedAt = t0,
    )
    private val foreign = ForeignPerpAccountSnapshot(
        signedQuantity = BigDecimal("-0.266"),
        entryPrice = BigDecimal("83549.4"),
        entryCommissionUsdt = BigDecimal("8.9"),
        fundingUsdt = BigDecimal("3.2"),
        takerFeeRate = BigDecimal("0.0005"),
        askLevels = listOf(PriceLevel(BigDecimal("81580"), BigDecimal("5.0"))),
        lastPrice = BigDecimal("81577"),
        askObservedAt = t0,
    )

    private fun position(koreaAvg: String = "113016278", foreignEntry: String = "83549.4") =
        AccountHedgePosition(
            korea.copy(averageBuyPrice = BigDecimal(koreaAvg)),
            foreign.copy(entryPrice = BigDecimal(foreignEntry)),
        )

    /** netPercent 만 지정한 관측. 판정은 scale 2 netPercent 만 본다. */
    private fun at(percent: String, position: AccountHedgePosition = position()): PnlObservation.Computed {
        val base = AccountNetPnl.calculate(position, BigDecimal("1328.5"), t0, t0)
        return PnlObservation.Computed(base.copy(netPercent = BigDecimal(percent)))
    }

    private fun episode(sentAt: Instant = t0, fingerprint: String = position().fingerprint) =
        PnlAlertState(true, fingerprint, sentAt)

    @Test
    fun `0_99에서 1_00으로 오르면 즉시 발송`() {
        val below = PnlAlertPolicy.decide(PnlAlertState.INITIAL, at("0.99"), t0)
        assertThat(below.send).isFalse()
        assertThat(below.next).isEqualTo(PnlAlertState.INITIAL)

        val now = t0.plusSeconds(60)
        val d = PnlAlertPolicy.decide(below.next, at("1.00"), now)

        assertThat(d.send).isTrue()
        assertThat(d.next).isEqualTo(PnlAlertState(true, position().fingerprint, now))
    }

    @Test
    fun `scale 이 달라도 1에 해당하면 발송한다`() {
        assertThat(PnlAlertPolicy.decide(PnlAlertState.INITIAL, at("1"), t0).send).isTrue()
    }

    @Test
    fun `에피소드 중 59분59초에는 재발송하지 않는다`() {
        val d = PnlAlertPolicy.decide(episode(), at("1.50"), t0.plusSeconds(59 * 60 + 59))

        assertThat(d.send).isFalse()
        assertThat(d.next).isEqualTo(episode())
    }

    @Test
    fun `에피소드 중 60분에 재발송한다`() {
        val now = t0.plusSeconds(60 * 60)
        val d = PnlAlertPolicy.decide(episode(), at("1.00"), now)

        assertThat(d.send).isTrue()
        assertThat(d.next).isEqualTo(episode(sentAt = now))
    }

    @Test
    fun `0_95에서는 60분이 지나도 재발송하지 않는다`() {
        val d = PnlAlertPolicy.decide(episode(), at("0.95"), t0.plusSeconds(3 * 60 * 60))

        assertThat(d.send).isFalse()
        assertThat(d.next).isEqualTo(episode())
    }

    @Test
    fun `0_89로 내려간 뒤 1_00이면 즉시 발송`() {
        val dropped = PnlAlertPolicy.decide(episode(), at("0.89"), t0.plusSeconds(60))
        assertThat(dropped.send).isFalse()
        assertThat(dropped.next).isEqualTo(PnlAlertState.INITIAL)

        val now = t0.plusSeconds(120)
        val d = PnlAlertPolicy.decide(dropped.next, at("1.00"), now)

        assertThat(d.send).isTrue()
        assertThat(d.next.lastSentAt).isEqualTo(now)
    }

    @Test
    fun `0_90에서는 재무장하지 않는다`() {
        val d = PnlAlertPolicy.decide(episode(), at("0.90"), t0.plusSeconds(60))

        assertThat(d.send).isFalse()
        assertThat(d.next).isEqualTo(episode())
    }

    @Test
    fun `포지션 없음이면 초기화`() {
        val d = PnlAlertPolicy.decide(episode(), PnlObservation.NoPosition, t0.plusSeconds(60))

        assertThat(d.send).isFalse()
        assertThat(d.next).isEqualTo(PnlAlertState.INITIAL)
    }

    @Test
    fun `계산 실패는 상태를 바꾸지 않는다`() {
        val state = episode()

        val d = PnlAlertPolicy.decide(state, PnlObservation.Failed, t0.plusSeconds(4 * 60 * 60))

        assertThat(d.send).isFalse()
        assertThat(d.next).isEqualTo(state)
        assertThat(PnlAlertPolicy.decide(PnlAlertState.INITIAL, PnlObservation.Failed, t0).next)
            .isEqualTo(PnlAlertState.INITIAL)
    }

    @Test
    fun `에피소드 중 바이낸스 쪽이 바뀌어 fingerprint 가 바뀌면 새 에피소드로 즉시 발송`() {
        val changed = position(foreignEntry = "83000")
        val now = t0.plusSeconds(60)

        val d = PnlAlertPolicy.decide(episode(), at("1.20", changed), now)

        assertThat(d.send).isTrue()
        assertThat(d.next).isEqualTo(PnlAlertState(true, changed.fingerprint, now))
    }

    @Test
    fun `에피소드 중 빗썸 쪽만 바뀌어도 새 에피소드로 즉시 발송`() {
        val changed = position(koreaAvg = "112000000")
        val now = t0.plusSeconds(60)

        val d = PnlAlertPolicy.decide(episode(), at("1.20", changed), now)

        assertThat(d.send).isTrue()
        assertThat(d.next).isEqualTo(PnlAlertState(true, changed.fingerprint, now))
    }

    @Test
    fun `fingerprint 가 바뀌고 1 미만이면 에피소드를 끝내고 발송하지 않는다`() {
        val changed = position(foreignEntry = "83000")

        val d = PnlAlertPolicy.decide(episode(), at("0.95", changed), t0.plusSeconds(60))

        assertThat(d.send).isFalse()
        assertThat(d.next).isEqualTo(PnlAlertState.INITIAL)
    }
}
