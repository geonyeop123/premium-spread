package io.premiumspread.domain.accountpnl

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId

class NetPercentSwingPolicyTest {

    private val zone = ZoneId.of("Asia/Seoul")
    private val threshold = BigDecimal("0.50")
    private val b1 = Instant.parse("2026-10-20T01:00:00Z")
    private val b2 = Instant.parse("2026-10-20T02:00:00Z")
    private val t0 = Instant.parse("2026-10-20T01:00:05Z")

    private val korea = KoreaSpotAccountSnapshot(
        quantity = BigDecimal("0.5"),
        averageBuyPrice = BigDecimal("100000000"),
        averageBuyPriceModified = false,
        entryFeeKrw = BigDecimal("12000"),
        sellFeeRate = BigDecimal("0.0004"),
        bidLevels = listOf(PriceLevel(BigDecimal("101000000"), BigDecimal("1.0"))),
        lastPrice = BigDecimal("101000000"),
        bidObservedAt = t0,
    )
    private val foreign = ForeignPerpAccountSnapshot(
        signedQuantity = BigDecimal("-0.5"),
        entryPrice = BigDecimal("70000"),
        entryCommissionUsdt = BigDecimal("8.9"),
        fundingUsdt = BigDecimal("3.2"),
        takerFeeRate = BigDecimal("0.0005"),
        askLevels = listOf(PriceLevel(BigDecimal("69000"), BigDecimal("5.0"))),
        lastPrice = BigDecimal("69000"),
        askObservedAt = t0,
    )

    private fun pnl(percent: String): AccountNetPnl =
        AccountNetPnl.calculate(AccountHedgePosition(korea, foreign), BigDecimal("1328.5"), t0, t0)
            .copy(netPercent = BigDecimal(percent))

    private fun decide(state: SwingState, bucket: Instant, percent: String?, changed: Boolean = false) =
        NetPercentSwingPolicy.decide(state, bucket, percent?.let { pnl(it) }, changed, threshold)

    private fun assertState(state: SwingState, bucket: Instant?, baseline: String?, up: Boolean, down: Boolean) {
        assertThat(state.bucketStart).isEqualTo(bucket)
        if (baseline == null) {
            assertThat(state.baselinePercent).isNull()
        } else {
            assertThat(state.baselinePercent).isEqualByComparingTo(BigDecimal(baseline))
        }
        assertThat(state.upSent).isEqualTo(up)
        assertThat(state.downSent).isEqualTo(down)
    }

    @Test
    fun `정시 버킷 경계는 zone 정시로 내림한다`() {
        assertThat(NetPercentSwingPolicy.bucketStart(Instant.parse("2026-10-20T01:59:59.999Z"), zone)).isEqualTo(b1)
        assertThat(NetPercentSwingPolicy.bucketStart(Instant.parse("2026-10-20T02:00:00Z"), zone)).isEqualTo(b2)
        assertThat(NetPercentSwingPolicy.bucketStart(Instant.parse("2026-10-20T00:00:05Z"), zone))
            .isEqualTo(Instant.parse("2026-10-20T00:00:00Z"))
    }

    @Test
    fun `새 버킷 첫 회차가 기준선이 되고 포지션이 없으면 기준선은 null 이다`() {
        val first = decide(SwingState.INITIAL, b1, "0.40")
        assertState(first.next, b1, "0.40", false, false)
        assertThat(first.alert).isNull()

        val none = decide(SwingState.INITIAL, b1, null)
        assertState(none.next, b1, null, false, false)
        assertThat(none.alert).isNull()
    }

    @Test
    fun `임계값 경계는 포함하고 같은 방향은 버킷 안에서 한 번만 알린다`() {
        val s0 = decide(SwingState.INITIAL, b1, "0.40").next

        assertThat(decide(s0, b1, "0.89").alert).isNull()
        assertThat(decide(s0, b1, "0.89").next).isEqualTo(s0)

        val up = decide(s0, b1, "0.90")
        assertThat(up.alert!!.direction).isEqualTo(SwingDirection.UP)
        assertThat(up.alert!!.delta).isEqualByComparingTo(BigDecimal("0.50"))
        assertState(up.next, b1, "0.40", true, false)

        val again = decide(up.next, b1, "1.20")
        assertThat(again.alert).isNull()
        assertThat(again.next).isEqualTo(up.next)

        val down = decide(up.next, b1, "-0.10")
        assertThat(down.alert!!.direction).isEqualTo(SwingDirection.DOWN)
        assertThat(down.alert!!.delta).isEqualByComparingTo(BigDecimal("-0.50"))
        assertState(down.next, b1, "0.40", true, true)

        val downAgain = decide(down.next, b1, "-0.50")
        assertThat(downAgain.alert).isNull()
        assertThat(downAgain.next).isEqualTo(down.next)
    }

    @Test
    fun `다음 버킷 첫 회차는 상태를 리셋하고 새 기준선을 잡는다`() {
        val sent = SwingState(b1, BigDecimal("0.40"), upSent = true, downSent = true)

        val next = decide(sent, b2, "1.20")

        assertState(next.next, b2, "1.20", false, false)
        assertThat(next.alert).isNull()
    }

    @Test
    fun `포지션 변경 회차는 기준선을 비우고 버킷 안에서 알리지 않으며 다음 버킷에 다시 잡는다`() {
        val s0 = decide(SwingState.INITIAL, b1, "0.40").next

        val changed = decide(s0, b1, "0.40", changed = true)
        assertState(changed.next, b1, null, false, false)
        assertThat(changed.alert).isNull()

        val jump = decide(changed.next, b1, "3.00")
        assertThat(jump.alert).isNull()
        assertThat(jump.next).isEqualTo(changed.next)

        val nextBucket = decide(jump.next, b2, "3.00")
        assertState(nextBucket.next, b2, "3.00", false, false)
        assertThat(nextBucket.alert).isNull()
    }

    @Test
    fun `알림은 방향 버킷 기준선 임계값 pnl 을 담는다`() {
        val s0 = decide(SwingState.INITIAL, b1, "0.40").next
        val observed = pnl("0.90")

        val alert = NetPercentSwingPolicy.decide(s0, b1, observed, false, threshold).alert!!

        assertThat(alert.direction).isEqualTo(SwingDirection.UP)
        assertThat(alert.bucketStart).isEqualTo(b1)
        assertThat(alert.baselinePercent).isEqualByComparingTo(BigDecimal("0.40"))
        assertThat(alert.threshold).isEqualByComparingTo(threshold)
        assertThat(alert.pnl).isEqualTo(observed)
    }
}
