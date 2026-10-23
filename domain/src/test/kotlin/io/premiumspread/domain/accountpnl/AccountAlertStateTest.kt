package io.premiumspread.domain.accountpnl

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

class AccountAlertStateTest {

    private fun bd(value: String) = BigDecimal(value)

    @Test
    fun `빗썸 수량이 dust 미만이면 빈 leg 이고 dust 경계는 유지한다`() {
        assertThat(KoreaLeg.of(bd("0.00009999"), bd("100000000"))).isEqualTo(KoreaLeg.EMPTY)
        assertThat(KoreaLeg.of(bd("0.00009999"), bd("100000000")).isEmpty).isTrue()

        val boundary = KoreaLeg.of(bd("0.0001"), bd("100000000"))

        assertThat(boundary.isEmpty).isFalse()
        assertThat(boundary.quantity).isEqualByComparingTo(bd("0.0001"))
        assertThat(boundary.averageBuyPrice).isEqualByComparingTo(bd("100000000"))
    }

    @Test
    fun `수량 scale 만 다른 빗썸 leg 는 같은 포지션이고 동등하다`() {
        val a = KoreaLeg.of(bd("0.50"), bd("100000000"))
        val b = KoreaLeg.of(bd("0.5"), bd("100000000"))

        assertThat(a).isEqualTo(b)
        assertThat(a.samePosition(b)).isTrue()
    }

    @Test
    fun `평단 scale 이 달라도 같은 포지션이다`() {
        val a = KoreaLeg.of(bd("0.5"), bd("100000000.00"))
        val b = KoreaLeg.of(bd("0.5"), bd("1E+8"))

        assertThat(a.samePosition(b)).isTrue()
        assertThat(a).isEqualTo(b)
    }

    @Test
    fun `samePosition 은 compareTo 로 비교한다`() {
        val a = KoreaLeg(bd("0.50"), bd("100000000.00"))
        val b = KoreaLeg(bd("0.5"), bd("1E+8"))

        assertThat(a).isNotEqualTo(b)
        assertThat(a.samePosition(b)).isTrue()
        assertThat(a.samePosition(KoreaLeg(bd("0.6"), bd("1E+8")))).isFalse()
        assertThat(ForeignLeg(bd("-0.50"), bd("70000.0")).samePosition(ForeignLeg(bd("-0.5"), bd("70000")))).isTrue()
    }

    @Test
    fun `바이낸스 수량이 0 이면 진입가와 무관하게 FLAT 이다`() {
        val leg = ForeignLeg.of(bd("0"), bd("70000"))

        assertThat(leg).isEqualTo(ForeignLeg.FLAT)
        assertThat(leg.isFlat).isTrue()
        assertThat(ForeignLeg.of(bd("-0.5"), bd("70000")).isFlat).isFalse()
    }

    @Test
    fun `스냅샷에서 leg 를 만든다`() {
        val at = Instant.parse("2026-10-20T00:00:00Z")
        val korea = KoreaSpotAccountSnapshot(
            quantity = bd("0.5"),
            averageBuyPrice = bd("100000000"),
            averageBuyPriceModified = false,
            entryFeeKrw = null,
            sellFeeRate = bd("0.0004"),
            bidLevels = emptyList(),
            lastPrice = bd("100000000"),
            bidObservedAt = at,
        )
        val foreign = ForeignPerpAccountSnapshot(
            signedQuantity = bd("-0.5"),
            entryPrice = bd("70000"),
            entryCommissionUsdt = null,
            fundingUsdt = null,
            takerFeeRate = bd("0.0005"),
            askLevels = emptyList(),
            lastPrice = bd("70000"),
            askObservedAt = at,
        )

        val legs = PositionLegs.of(korea, foreign)

        assertThat(legs.korea).isEqualTo(KoreaLeg.of(bd("0.5"), bd("100000000")))
        assertThat(legs.foreign).isEqualTo(ForeignLeg.of(bd("-0.5"), bd("70000")))
    }

    @Test
    fun `급변 상태는 버킷 없이 값을 가질 수 없다`() {
        assertThatThrownBy { SwingState(null, bd("0.40"), false, false) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("swing state without a bucket must be empty.")
        assertThatThrownBy { SwingState(null, null, true, false) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThat(SwingState.INITIAL).isEqualTo(SwingState(null, null, false, false))
    }

    @Test
    fun `리마인더 상태는 변경 시각 없이 펀딩 값을 가질 수 없다`() {
        assertThatThrownBy { ReminderState(null, BigDecimal.ONE, null) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("manual funding value requires its change time.")
    }

    @Test
    fun `설정 기본값`() {
        val settings = AccountAlertSettings()

        assertThat(settings.swingThreshold).isEqualTo(bd("0.50"))
        assertThat(settings.reminderHour).isEqualTo(9)
        assertThat(settings.manualFundingStaleAfter).isEqualTo(Duration.ofDays(3))
    }

    @Test
    fun `설정 범위를 벗어나면 거부한다`() {
        listOf("0", "-0.1").forEach {
            assertThatThrownBy { AccountAlertSettings(swingThreshold = bd(it)) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessage("swingThreshold must be positive.")
        }
        listOf(-1, 24).forEach {
            assertThatThrownBy { AccountAlertSettings(reminderHour = it) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessage("reminderHour must be between 0 and 23.")
        }
        assertThatThrownBy { AccountAlertSettings(manualFundingStaleAfter = Duration.ZERO) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("manualFundingStaleAfter must be positive.")
        assertThat(AccountAlertSettings(reminderHour = 0).reminderHour).isZero()
        assertThat(AccountAlertSettings(reminderHour = 23).reminderHour).isEqualTo(23)
    }

    @Test
    fun `3인자 모니터 상태의 account 는 INITIAL 이다`() {
        val state = AccountPnlMonitorState(PnlAlertState.INITIAL, OutageState.INITIAL, 3)

        assertThat(state.account).isEqualTo(AccountAlertState.INITIAL)
        assertThat(AccountPnlMonitorState.INITIAL.account).isEqualTo(AccountAlertState.INITIAL)
    }
}
