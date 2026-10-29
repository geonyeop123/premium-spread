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

class PremiumExtremePolicyTest {
    private val now = Instant.parse("2026-10-29T00:20:30Z")
    private val floorMinute = now.truncatedTo(ChronoUnit.MINUTES)
    private val floorHour = now.truncatedTo(ChronoUnit.HOURS)
    private val pair = MarketPair.default(Symbol("BTC"))
    private val pairKey = MarketKeys.pairKey(pair)
    private val settings = MarketAlertSettings()

    private fun key(window: ExtremeWindow, direction: MarketDirection) = MarketKeys.extreme(pairKey, window, direction)

    private fun state(window: ExtremeWindow, direction: MarketDirection, last: Instant? = null, version: Long = 4) =
        MarketAlertState(key(window, direction), OwnerAlertKind.PREMIUM_NEW_EXTREME, last, null, null, null, version)

    private fun states(
        hourHighLast: Instant? = null,
        dayHighLast: Instant? = null,
        hourLowLast: Instant? = null,
        dayLowLast: Instant? = null,
    ) = ExtremeStates(
        hourHigh = state(ExtremeWindow.ONE_HOUR, MarketDirection.UP, hourHighLast),
        hourLow = state(ExtremeWindow.ONE_HOUR, MarketDirection.DOWN, hourLowLast),
        dayHigh = state(ExtremeWindow.ONE_DAY, MarketDirection.UP, dayHighLast),
        dayLow = state(ExtremeWindow.ONE_DAY, MarketDirection.DOWN, dayLowLast),
    )

    private fun current(rate: String) = PremiumSnapshot(
        pair,
        BigDecimal(rate),
        BigDecimal("100"),
        BigDecimal("1"),
        BigDecimal("100"),
        BigDecimal("1400"),
        now,
    )

    private fun bucket(at: Instant, high: String, low: String, close: String = "2.00") = PremiumAggregationSnapshot(
        pair = pair,
        high = BigDecimal(high),
        low = BigDecimal(low),
        open = BigDecimal(close),
        close = BigDecimal(close),
        avg = BigDecimal(close),
        count = 10,
        observedAt = at,
    )

    /** 1..80 분 전 bucket(23:00~00:19). high = low = 2.00, [overrides] 는 분 전 -> (high, low). */
    private fun minutes(
        skipAgo: Set<Long> = emptySet(),
        overrides: Map<Long, Pair<String, String>> = emptyMap(),
    ) = (1L..80L).filter { it !in skipAgo }.map { k ->
        val (high, low) = overrides[k] ?: ("2.00" to "2.00")
        bucket(floorMinute.minus(Duration.ofMinutes(k)), high, low)
    }

    /** 24..2 시간 전 시 bucket 23개 중 앞의 [count] 개. [overrides] 는 시간 전 -> (high, low). */
    private fun hours(count: Int = 23, overrides: Map<Long, Pair<String, String>> = emptyMap()) =
        (24L downTo 2L).take(count).map { k ->
            val (high, low) = overrides[k] ?: ("2.00" to "2.00")
            bucket(floorHour.minus(Duration.ofHours(k)), high, low)
        }

    private fun observation(
        current: String?,
        minutes: List<PremiumAggregationSnapshot>? = minutes(),
        hours: List<PremiumAggregationSnapshot>? = hours(),
    ) = MarketObservation(pair, current?.let { current(it) }, minutes, hours)

    private fun decide(observation: MarketObservation, states: ExtremeStates = states()) =
        PremiumExtremePolicy.decide(observation, states, settings, now)

    private fun windows(decision: MarketDecision) = decision.notices.map { (it as MarketNotice.NewExtreme).window }

    private fun dayHighBucket() = hours(overrides = mapOf(10L to ("2.50" to "2.00")))

    @Test
    fun `24h 신고가면 24h 알림과 1h 쿨다운을 함께 기록한다`() {
        val decision = decide(observation("2.60", hours = dayHighBucket()))

        assertThat(decision.notices).containsExactly(
            MarketNotice.NewExtreme(
                pair,
                now,
                ExtremeWindow.ONE_DAY,
                MarketDirection.UP,
                BigDecimal("2.50"),
                BigDecimal("2.60"),
                now,
            ),
        )
        assertThat(decision.next).containsExactlyInAnyOrder(
            state(ExtremeWindow.ONE_DAY, MarketDirection.UP, now),
            state(ExtremeWindow.ONE_HOUR, MarketDirection.UP, now),
        )
    }

    @Test
    fun `24h 고점에 0_1 모자라면 알리지 않는다`() {
        // 1h 구간에도 고가 2.50 이 있어야 1h 도 미발동이다.
        val minutes = minutes(overrides = mapOf(5L to ("2.50" to "2.00")))

        assertThat(decide(observation("2.5999", minutes, dayHighBucket()))).isEqualTo(MarketDecision.NONE)
        assertThat(windows(decide(observation("2.60", minutes, dayHighBucket())))).containsExactly(ExtremeWindow.ONE_DAY)
    }

    @Test
    fun `1h 신고가만 참이면 1h 알림만 낸다`() {
        val hours = hours(overrides = mapOf(10L to ("3.00" to "2.00")))

        val decision = decide(observation("2.10", hours = hours))

        assertThat(decision.notices).containsExactly(
            MarketNotice.NewExtreme(
                pair,
                now,
                ExtremeWindow.ONE_HOUR,
                MarketDirection.UP,
                BigDecimal("2.00"),
                BigDecimal("2.10"),
                now,
            ),
        )
        assertThat(decision.next).containsExactly(state(ExtremeWindow.ONE_HOUR, MarketDirection.UP, now))
    }

    @Test
    fun `24h 쿨다운 중이면 1h 조건이 참이어도 알리지 않고 경계에서 다시 알린다`() {
        val obs = observation("2.60", hours = dayHighBucket())
        val during = states(dayHighLast = now.minus(Duration.ofHours(2)).plusMillis(1))
        val boundary = states(dayHighLast = now.minus(Duration.ofHours(2)))

        assertThat(decide(obs, during)).isEqualTo(MarketDecision.NONE)
        assertThat(windows(decide(obs, boundary))).containsExactly(ExtremeWindow.ONE_DAY)
    }

    @Test
    fun `1h 쿨다운 경계는 30분 이상이면 알린다`() {
        val obs = observation("2.10", hours = hours(overrides = mapOf(10L to ("3.00" to "2.00"))))
        val boundary = states(hourHighLast = now.minus(Duration.ofMinutes(30)))
        val during = states(hourHighLast = now.minus(Duration.ofMinutes(30)).plusMillis(1))

        assertThat(decide(obs, boundary).notices).hasSize(1)
        assertThat(decide(obs, during)).isEqualTo(MarketDecision.NONE)
    }

    @Test
    fun `시 bucket 이 18개면 24h 는 판정하지 않고 1h 만 판정한다`() {
        val obs = observation("2.60", hours = hours(count = 18, overrides = mapOf(10L to ("2.50" to "2.00"))))

        val decision = decide(obs)

        assertThat(windows(decision)).containsExactly(ExtremeWindow.ONE_HOUR)
        assertThat(decision.next).containsExactly(state(ExtremeWindow.ONE_HOUR, MarketDirection.UP, now))
    }

    @Test
    fun `hours 가 null 이면 24h 는 판정하지 않고 1h 만 판정한다`() {
        assertThat(windows(decide(observation("2.60", hours = null)))).containsExactly(ExtremeWindow.ONE_HOUR)
    }

    @Test
    fun `시 bucket 이 19개면 24h 를 판정한다`() {
        val obs = observation("2.60", hours = hours(count = 19, overrides = mapOf(24L to ("2.50" to "2.00"))))

        assertThat(windows(decide(obs))).containsExactly(ExtremeWindow.ONE_DAY)
    }

    @Test
    fun `1h 구간 분 bucket 이 47개면 1h 는 판정하지 않는다`() {
        val hours = hours(overrides = mapOf(10L to ("3.00" to "2.00")))

        assertThat(decide(observation("2.10", minutes(skipAgo = (1L..13L).toSet()), hours)))
            .isEqualTo(MarketDecision.NONE)
    }

    @Test
    fun `신저가는 대칭이다`() {
        val decision = decide(observation("1.40", hours = hours(overrides = mapOf(10L to ("2.00" to "1.50")))))

        assertThat(decision.notices).containsExactly(
            MarketNotice.NewExtreme(
                pair,
                now,
                ExtremeWindow.ONE_DAY,
                MarketDirection.DOWN,
                BigDecimal("1.50"),
                BigDecimal("1.40"),
                now,
            ),
        )
        assertThat(decision.next).containsExactlyInAnyOrder(
            state(ExtremeWindow.ONE_DAY, MarketDirection.DOWN, now),
            state(ExtremeWindow.ONE_HOUR, MarketDirection.DOWN, now),
        )
    }

    @Test
    fun `1h 신저가만 참이면 1h 알림만 낸다`() {
        val hours = hours(overrides = mapOf(10L to ("2.00" to "1.00")))

        val decision = decide(observation("1.90", hours = hours))

        assertThat(windows(decision)).containsExactly(ExtremeWindow.ONE_HOUR)
        assertThat(decision.next).containsExactly(state(ExtremeWindow.ONE_HOUR, MarketDirection.DOWN, now))
    }

    @Test
    fun `기준은 종가가 아니라 고가와 저가다`() {
        // 종가는 모두 2.00 이지만 한 분 bucket 의 고가가 2.55 - 현재 2.60 은 0.05 만 넘는다.
        val minutes = minutes(overrides = mapOf(3L to ("2.55" to "2.00")))

        assertThat(decide(observation("2.60", minutes, hours = null))).isEqualTo(MarketDecision.NONE)
    }

    @Test
    fun `current 나 minutes 가 null 이면 판정하지 않는다`() {
        assertThat(decide(observation(null))).isEqualTo(MarketDecision.NONE)
        assertThat(decide(observation("2.60", minutes = null))).isEqualTo(MarketDecision.NONE)
    }

    @Test
    fun `상태 key 가 뒤바뀌면 예외다`() {
        val swapped = states().let { it.copy(hourHigh = it.dayHigh, dayHigh = it.hourHigh) }

        assertThatThrownBy { decide(observation("2.60"), swapped) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("market state key mismatch.")
    }
}
