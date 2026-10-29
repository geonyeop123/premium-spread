package io.premiumspread.domain.ownermarket

import io.premiumspread.domain.market.MarketPair
import io.premiumspread.domain.owneralert.OwnerAlertKind
import io.premiumspread.domain.premium.PremiumAggregationSnapshot
import io.premiumspread.domain.premium.PremiumSnapshot
import io.premiumspread.domain.ticker.Symbol
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

class PremiumSwingPolicyTest {
    private val now = Instant.parse("2026-10-29T00:00:30Z")
    private val floorMinute = now.truncatedTo(ChronoUnit.MINUTES)
    private val pair = MarketPair.default(Symbol("BTC"))
    private val pairKey = MarketKeys.pairKey(pair)
    private val settings = MarketAlertSettings()
    private val upKey = MarketKeys.swing(pairKey, MarketDirection.UP)
    private val downKey = MarketKeys.swing(pairKey, MarketDirection.DOWN)

    private fun up(lastNotifiedAt: Instant? = null, version: Long = 3) =
        MarketAlertState(upKey, OwnerAlertKind.PREMIUM_SWING, lastNotifiedAt, null, null, null, version)

    private fun down(lastNotifiedAt: Instant? = null, version: Long = 5) =
        MarketAlertState(downKey, OwnerAlertKind.PREMIUM_SWING, lastNotifiedAt, null, null, null, version)

    private fun current(rate: String) = PremiumSnapshot(
        pair, BigDecimal(rate), BigDecimal("100"), BigDecimal("1"), BigDecimal("100"), BigDecimal("1400"), now,
    )

    private fun bucket(minutesAgo: Long, close: String, high: String = close, low: String = close) =
        PremiumAggregationSnapshot(
            pair = pair,
            high = BigDecimal(high),
            low = BigDecimal(low),
            open = BigDecimal(close),
            close = BigDecimal(close),
            avg = BigDecimal(close),
            count = 10,
            observedAt = floorMinute.minus(Duration.ofMinutes(minutesAgo)),
        )

    /** 1..10 분 전 bucket 10개. 종가는 2.50, [lowClose]·[highClose] 가 있으면 각각 한 개를 바꾼다. */
    private fun tenBuckets(lowClose: String? = null, highClose: String? = null): List<PremiumAggregationSnapshot> =
        (1L..10L).map { k ->
            val close = when {
                k == 3L && lowClose != null -> lowClose

                k == 5L && highClose != null -> highClose

                else -> "2.50"
            }
            bucket(k, close)
        }

    private fun observation(current: String?, minutes: List<PremiumAggregationSnapshot>?) =
        MarketObservation(pair, current?.let { current(it) }, minutes, emptyList())

    private fun decide(
        obs: MarketObservation,
        upState: MarketAlertState = up(),
        downState: MarketAlertState = down(),
    ) = PremiumSwingPolicy.decide(obs, upState, downState, settings, now)

    @Test
    fun `저점 2_00에서 2_50이면 UP을 알리고 상태를 기록한다`() {
        val decision = decide(observation("2.50", tenBuckets(lowClose = "2.00")))

        val swing = decision.notices.single() as MarketNotice.Swing
        assertThat(swing.direction).isEqualTo(MarketDirection.UP)
        assertThat(swing.reference).isEqualByComparingTo("2.00")
        assertThat(swing.delta).isEqualByComparingTo("0.50")
        assertThat(swing.current).isEqualByComparingTo("2.50")
        assertThat(swing.currentObservedAt).isEqualTo(now)
        assertThat(swing.window).isEqualTo(Duration.ofMinutes(10))
        assertThat(swing.at).isEqualTo(now)
        assertThat(swing.pair).isEqualTo(pair)
        assertThat(decision.next).containsExactly(up(lastNotifiedAt = now))
    }

    @Test
    fun `저점 2_00에서 2_49면 알리지 않는다`() {
        val decision = decide(observation("2.49", tenBuckets(lowClose = "2.00")))

        assertThat(decision).isEqualTo(MarketDecision.NONE)
    }

    @Test
    fun `고점 3_00에서 2_50이면 DOWN을 알린다`() {
        val decision = decide(observation("2.50", tenBuckets(highClose = "3.00")))

        val swing = decision.notices.single() as MarketNotice.Swing
        assertThat(swing.direction).isEqualTo(MarketDirection.DOWN)
        assertThat(swing.reference).isEqualByComparingTo("3.00")
        assertThat(swing.delta).isEqualByComparingTo("-0.50")
        assertThat(decision.next).containsExactly(down(lastNotifiedAt = now))
    }

    @Test
    fun `저점과 고점이 모두 벌어졌으면 양방향을 함께 알린다`() {
        val decision = decide(observation("2.50", tenBuckets(lowClose = "2.00", highClose = "3.00")))

        assertThat(decision.notices.map { (it as MarketNotice.Swing).direction })
            .containsExactlyInAnyOrder(MarketDirection.UP, MarketDirection.DOWN)
        assertThat(decision.next).containsExactlyInAnyOrder(up(lastNotifiedAt = now), down(lastNotifiedAt = now))
    }

    @Test
    fun `쿨다운 경계 now-30m 은 알리고 1ms 모자라면 알리지 않는다`() {
        val obs = observation("2.50", tenBuckets(lowClose = "2.00"))
        val boundary = now.minus(Duration.ofMinutes(30))

        val fired = decide(obs, upState = up(lastNotifiedAt = boundary))
        val held = decide(obs, upState = up(lastNotifiedAt = boundary.plusMillis(1)))

        assertThat(fired.notices).hasSize(1)
        assertThat(fired.next).containsExactly(up(lastNotifiedAt = now))
        assertThat(held.notices).isEmpty()
        assertThat(held.next).isEmpty()
    }

    @Test
    fun `창 밖 11분 전 bucket 의 종가는 쓰지 않는다`() {
        val minutes = tenBuckets() + bucket(11, "1.00")

        val decision = decide(observation("2.50", minutes))

        assertThat(decision).isEqualTo(MarketDecision.NONE)
    }

    @Test
    fun `창 안 bucket 이 7개면 근거 부족이고 8개면 충분하다`() {
        val seven = (1L..7L).map { k -> bucket(k, if (k == 3L) "2.00" else "2.50") }
        val eight = (1L..8L).map { k -> bucket(k, if (k == 3L) "2.00" else "2.50") }

        assertThat(decide(observation("2.50", seven))).isEqualTo(MarketDecision.NONE)
        assertThat(decide(observation("2.50", eight)).notices).hasSize(1)
    }

    @Test
    fun `고가가 튀어도 종가만 본다`() {
        val minutes = (1L..10L).map { k -> bucket(k, "2.50", high = if (k == 2L) "9.99" else "2.50", low = "0.01") }

        val decision = decide(observation("2.50", minutes))

        assertThat(decision).isEqualTo(MarketDecision.NONE)
    }

    @Test
    fun `현재값이나 분봉을 모르면 아무것도 하지 않는다`() {
        assertThat(decide(observation(null, tenBuckets(lowClose = "2.00")))).isEqualTo(MarketDecision.NONE)
        assertThat(decide(observation("2.50", null))).isEqualTo(MarketDecision.NONE)
    }

    @Test
    fun `up 자리에 swing-down 상태를 넣으면 key 불일치 예외다`() {
        val obs = observation("2.50", tenBuckets(lowClose = "2.00"))

        assertThatThrownBy { decide(obs, upState = down(), downState = down()) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("market state key mismatch.")
        assertThatThrownBy { decide(observation(null, null), upState = up(), downState = up()) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("market state key mismatch.")
    }
}
