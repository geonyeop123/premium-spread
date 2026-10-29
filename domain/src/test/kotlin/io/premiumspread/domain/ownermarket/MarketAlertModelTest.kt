package io.premiumspread.domain.ownermarket

import io.premiumspread.domain.market.MarketPair
import io.premiumspread.domain.owneralert.OwnerAlertKind
import io.premiumspread.domain.premium.PremiumSnapshot
import io.premiumspread.domain.ticker.Exchange
import io.premiumspread.domain.ticker.Symbol
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class MarketAlertModelTest {
    private val now = Instant.parse("2026-10-29T00:30:15Z")
    private val pair = MarketPair.default(Symbol("BTC"))
    private val otherPair = MarketPair(Symbol("BTC"), Exchange.UPBIT, Exchange.BINANCE)

    @Test
    fun `pair key는 소문자 세 부분이다`() {
        assertThat(MarketKeys.pairKey(pair)).isEqualTo("btc-bithumb-binance")
    }

    @Test
    fun `pair key가 41자면 거부한다`() {
        val ok = MarketPair.default(Symbol("A".repeat(24)))
        val tooLong = MarketPair.default(Symbol("A".repeat(25)))

        assertThat(MarketKeys.pairKey(ok)).hasSize(40)
        assertThatThrownBy { MarketKeys.pairKey(tooLong) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("market pair key must be [a-z0-9]+ parts within 40 chars.")
    }

    @Test
    fun `pair key 부분에 영숫자 밖 문자가 있으면 거부한다`() {
        assertThatThrownBy { MarketKeys.pairKey(MarketPair.default(Symbol("BTC_X"))) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("market pair key must be [a-z0-9]+ parts within 40 chars.")
    }

    @Test
    fun `상태 key 8개가 설계 표와 같다`() {
        val pk = "btc-bithumb-binance"

        assertThat(
            listOf(
                MarketKeys.swing(pk, MarketDirection.UP),
                MarketKeys.swing(pk, MarketDirection.DOWN),
                MarketKeys.threshold(pk),
                MarketKeys.extreme(pk, ExtremeWindow.ONE_HOUR, MarketDirection.UP),
                MarketKeys.extreme(pk, ExtremeWindow.ONE_HOUR, MarketDirection.DOWN),
                MarketKeys.extreme(pk, ExtremeWindow.ONE_DAY, MarketDirection.UP),
                MarketKeys.extreme(pk, ExtremeWindow.ONE_DAY, MarketDirection.DOWN),
                MarketKeys.dailySummary(pk),
            ),
        ).containsExactly(
            "market.btc-bithumb-binance.swing-up",
            "market.btc-bithumb-binance.swing-down",
            "market.btc-bithumb-binance.threshold",
            "market.btc-bithumb-binance.extreme-1h-high",
            "market.btc-bithumb-binance.extreme-1h-low",
            "market.btc-bithumb-binance.extreme-24h-high",
            "market.btc-bithumb-binance.extreme-24h-low",
            "market.btc-bithumb-binance.daily-summary",
        )
    }

    @Test
    fun `requireKey는 같은 key만 통과시킨다`() {
        val state = MarketAlertState.initial("market.a.swing-up", OwnerAlertKind.PREMIUM_SWING)

        assertThatCode { MarketKeys.requireKey(state, "market.a.swing-up") }.doesNotThrowAnyException()
        assertThatThrownBy { MarketKeys.requireKey(state, "market.a.swing-down") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("market state key mismatch.")
    }

    @Test
    fun `설정 기본값은 설계와 같고 기준선은 꺼져 있다`() {
        val s = MarketAlertSettings()

        assertThat(s.swingWindow).isEqualTo(Duration.ofMinutes(10))
        assertThat(s.swingThreshold).isEqualByComparingTo("0.5")
        assertThat(s.swingCooldown).isEqualTo(Duration.ofMinutes(30))
        assertThat(s.upperThreshold).isNull()
        assertThat(s.lowerThreshold).isNull()
        assertThat(s.thresholdHysteresis).isEqualByComparingTo("0.1")
        assertThat(s.extremeMinDelta).isEqualByComparingTo("0.1")
        assertThat(s.extremeHourCooldown).isEqualTo(Duration.ofMinutes(30))
        assertThat(s.extremeDayCooldown).isEqualTo(Duration.ofHours(2))
        assertThat(s.summaryHour).isEqualTo(9)
        assertThat(s.summaryPnlMaxAge).isEqualTo(Duration.ofMinutes(10))
        assertThat(s.thresholdBaseline).isNull()
    }

    @Test
    fun `기준선은 upper lower hysteresis를 담는다`() {
        val s = MarketAlertSettings(upperThreshold = BigDecimal("2.21"), lowerThreshold = BigDecimal("2.0"))

        assertThat(s.thresholdBaseline!!.sameAs(ThresholdBaseline(BigDecimal("2.21"), BigDecimal("2.0"), BigDecimal("0.1"))))
            .isTrue()
        assertThat(MarketAlertSettings(upperThreshold = BigDecimal("2.5")).thresholdBaseline!!.lower).isNull()
    }

    @Test
    fun `swingWindow는 1분에서 60분의 정수 분만 받는다`() {
        val message = "swingWindow must be whole minutes between 1m and 60m."
        listOf(Duration.ZERO, Duration.ofMinutes(61), Duration.ofSeconds(90)).forEach {
            assertThatThrownBy { MarketAlertSettings(swingWindow = it) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessage(message)
        }
        assertThatCode { MarketAlertSettings(swingWindow = Duration.ofMinutes(1)) }.doesNotThrowAnyException()
        assertThatCode { MarketAlertSettings(swingWindow = Duration.ofMinutes(60)) }.doesNotThrowAnyException()
    }

    @Test
    fun `양수여야 하는 값은 0을 거부한다`() {
        fun fails(message: String, build: () -> MarketAlertSettings) {
            assertThatThrownBy { build() }.isInstanceOf(IllegalArgumentException::class.java).hasMessage(message)
        }
        fails("swingThreshold must be positive.") { MarketAlertSettings(swingThreshold = BigDecimal.ZERO) }
        fails("swingCooldown must be positive.") { MarketAlertSettings(swingCooldown = Duration.ZERO) }
        fails("extremeMinDelta must be positive.") { MarketAlertSettings(extremeMinDelta = BigDecimal.ZERO) }
        fails("extremeHourCooldown must be positive.") { MarketAlertSettings(extremeHourCooldown = Duration.ZERO) }
        fails("extremeDayCooldown must be positive.") { MarketAlertSettings(extremeDayCooldown = Duration.ZERO) }
        fails("summaryPnlMaxAge must be positive.") { MarketAlertSettings(summaryPnlMaxAge = Duration.ZERO) }
    }

    @Test
    fun `hysteresis 음수와 upper lower 간격을 검증한다`() {
        assertThatThrownBy { MarketAlertSettings(thresholdHysteresis = BigDecimal("-0.1")) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("thresholdHysteresis must not be negative.")
        // 차 0.2 = 2h 는 거부, 2.21 - 2.0 = 0.21 > 0.2 는 통과
        assertThatThrownBy {
            MarketAlertSettings(upperThreshold = BigDecimal("2.2"), lowerThreshold = BigDecimal("2.0"))
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("upperThreshold must exceed lowerThreshold by more than twice the hysteresis.")
        assertThatCode {
            MarketAlertSettings(upperThreshold = BigDecimal("2.21"), lowerThreshold = BigDecimal("2.0"))
        }.doesNotThrowAnyException()
    }

    @Test
    fun `기준선 값은 scale 4 이하이고 절대값 100만 미만이다`() {
        val message = "threshold values must have at most 4 decimal places and an absolute value below 1000000."
        assertThatThrownBy { MarketAlertSettings(upperThreshold = BigDecimal("3.12345")) }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessage(message)
        assertThatThrownBy { MarketAlertSettings(lowerThreshold = BigDecimal("-1000000")) }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessage(message)
        assertThatThrownBy { MarketAlertSettings(thresholdHysteresis = BigDecimal("0.00001")) }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessage(message)

        assertThatCode { MarketAlertSettings(upperThreshold = BigDecimal("3.1234")) }.doesNotThrowAnyException()
        assertThatCode { MarketAlertSettings(lowerThreshold = BigDecimal("-999999.9999")) }.doesNotThrowAnyException()
    }

    @Test
    fun `summaryHour는 1에서 23이다`() {
        listOf(0, 24).forEach {
            assertThatThrownBy { MarketAlertSettings(summaryHour = it) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessage("summaryHour must be between 1 and 23.")
        }
        assertThatCode { MarketAlertSettings(summaryHour = 1) }.doesNotThrowAnyException()
        assertThatCode { MarketAlertSettings(summaryHour = 23) }.doesNotThrowAnyException()
    }

    @Test
    fun `상태 불변식을 거부한다`() {
        val swing = OwnerAlertKind.PREMIUM_SWING
        val baseline = ThresholdBaseline(BigDecimal("2.5"), null, BigDecimal("0.1"))

        fun fails(message: String, build: () -> MarketAlertState) {
            assertThatThrownBy { build() }.isInstanceOf(IllegalArgumentException::class.java).hasMessage(message)
        }
        fails("signalKey must match [a-z0-9._-]{1,64}.") { MarketAlertState.initial("Bad Key", swing) }
        fails("signalKey must match [a-z0-9._-]{1,64}.") { MarketAlertState.initial("a".repeat(65), swing) }
        fails("version must not be negative.") { MarketAlertState("market.a", swing, null, null, null, null, -1) }
        fails("zone and baseline must be set together.") {
            MarketAlertState("market.a", OwnerAlertKind.PREMIUM_THRESHOLD_CROSSED, null, PremiumZone.ABOVE, null, null, 0)
        }
        fails("zone is only for threshold signals.") {
            MarketAlertState("market.a", swing, null, PremiumZone.ABOVE, baseline, null, 0)
        }
        fails("summaryDate is only for daily summary signals.") {
            MarketAlertState("market.a", swing, null, null, null, LocalDate.parse("2026-10-28"), 0)
        }
    }

    @Test
    fun `initial은 선택 필드가 모두 null이고 version 0이다`() {
        val s = MarketAlertState.initial("market.a.daily-summary", OwnerAlertKind.DAILY_SUMMARY)

        assertThat(s.lastNotifiedAt).isNull()
        assertThat(s.zone).isNull()
        assertThat(s.baseline).isNull()
        assertThat(s.summaryDate).isNull()
        assertThat(s.version).isZero()
    }

    @Test
    fun `sameAs는 scale을 무시하고 null은 null과만 같다`() {
        val a = ThresholdBaseline(BigDecimal("2.5"), null, BigDecimal("0.1"))

        assertThat(a.sameAs(ThresholdBaseline(BigDecimal("2.5000"), null, BigDecimal("0.10")))).isTrue()
        assertThat(a.sameAs(ThresholdBaseline(null, BigDecimal("2.5"), BigDecimal("0.1")))).isFalse()
        assertThat(a.sameAs(ThresholdBaseline(BigDecimal("2.5"), BigDecimal("1"), BigDecimal("0.1")))).isFalse()
        assertThat(a.sameAs(ThresholdBaseline(BigDecimal("2.5"), null, BigDecimal("0.2")))).isFalse()
    }

    @Test
    fun `창 세 개가 설계 값과 같다`() {
        val w = MarketWindows.of(now, ZoneId.of("Asia/Seoul"))

        assertThat(w.minutes.from).isEqualTo(Instant.parse("2026-10-28T23:00:00Z"))
        assertThat(w.minutes.to).isEqualTo(Instant.parse("2026-10-29T00:30:00Z"))
        assertThat(w.hours.from).isEqualTo(Instant.parse("2026-10-28T00:00:00Z"))
        assertThat(w.hours.to).isEqualTo(Instant.parse("2026-10-28T23:00:00Z"))
        assertThat(w.previousDay.from).isEqualTo(Instant.parse("2026-10-27T15:00:00Z"))
        assertThat(w.previousDay.to).isEqualTo(Instant.parse("2026-10-28T15:00:00Z"))
        assertThat(w.previousDay.zone.toString()).isEqualTo("Asia/Seoul")
    }

    @Test
    fun `현재값은 나이 10초까지 유지하고 그 뒤는 버린다`() {
        fun at(observedAt: Instant, p: MarketPair = pair) = snapshot(p, observedAt)

        val exact = at(now.minusSeconds(10))
        assertThat(MarketEvidence.current(exact, pair, now)).isSameAs(exact)
        assertThat(MarketEvidence.current(at(now.minusSeconds(10).minusMillis(1)), pair, now)).isNull()
        val future = at(now.plusSeconds(5))
        assertThat(MarketEvidence.current(future, pair, now)).isSameAs(future)
        assertThat(MarketEvidence.current(at(now, otherPair), pair, now)).isNull()
        assertThat(MarketEvidence.current(null, pair, now)).isNull()
    }

    @Test
    fun `충분성은 기대 수의 80퍼센트 경계다`() {
        assertThat(MarketEvidence.sufficient(8, 10)).isTrue()
        assertThat(MarketEvidence.sufficient(7, 10)).isFalse()
        assertThat(MarketEvidence.sufficient(48, 60)).isTrue()
        assertThat(MarketEvidence.sufficient(19, 23)).isTrue()
        assertThat(MarketEvidence.sufficient(18, 23)).isFalse()
        assertThatThrownBy { MarketEvidence.sufficient(1, 0) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("expected must be at least 1.")
    }

    private fun snapshot(p: MarketPair, observedAt: Instant) = PremiumSnapshot(
        pair = p,
        premiumRate = BigDecimal("1.0"),
        koreaPrice = BigDecimal("100000000"),
        foreignPrice = BigDecimal("70000"),
        foreignPriceInKrw = BigDecimal("98000000"),
        fxRate = BigDecimal("1400"),
        observedAt = observedAt,
    )
}
