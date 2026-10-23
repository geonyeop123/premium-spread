package io.premiumspread.domain.accountpnl

import io.premiumspread.domain.owneralert.OwnerAlertKind
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId

class AccountAlertPolicyTest {

    private val zone = ZoneId.of("Asia/Seoul")
    private val settings = AccountAlertSettings()
    private val manual = ForeignPerpPositionSource.MANUAL_ENV
    private val computedAt = Instant.parse("2026-10-20T00:30:00Z")
    private val bucket = Instant.parse("2026-10-20T00:00:00Z")

    private val legs = PositionLegs(
        KoreaLeg.of(BigDecimal("0.5"), BigDecimal("100000000")),
        ForeignLeg.of(BigDecimal("-0.5"), BigDecimal("70000")),
    )
    private val otherLegs = PositionLegs(
        KoreaLeg.of(BigDecimal("0.6"), BigDecimal("100000000")),
        legs.foreign,
    )

    private fun pnl(at: Instant, unrecovered: Boolean = false): AccountNetPnl {
        val korea = KoreaSpotAccountSnapshot(
            quantity = BigDecimal("0.5"),
            averageBuyPrice = BigDecimal("100000000"),
            averageBuyPriceModified = false,
            entryFeeKrw = if (unrecovered) null else BigDecimal("12000"),
            sellFeeRate = BigDecimal("0.0004"),
            bidLevels = listOf(PriceLevel(BigDecimal("101000000"), BigDecimal("1.0"))),
            lastPrice = BigDecimal("101000000"),
            bidObservedAt = at,
        )
        val foreign = ForeignPerpAccountSnapshot(
            signedQuantity = BigDecimal("-0.5"),
            entryPrice = BigDecimal("70000"),
            entryCommissionUsdt = BigDecimal("8.9"),
            fundingUsdt = BigDecimal("3.2"),
            takerFeeRate = BigDecimal("0.0005"),
            askLevels = listOf(PriceLevel(BigDecimal("69000"), BigDecimal("5.0"))),
            lastPrice = BigDecimal("69000"),
            askObservedAt = at,
        )
        return AccountNetPnl.calculate(AccountHedgePosition(korea, foreign), BigDecimal("1328.5"), at, at)
    }

    private fun round(
        at: Instant = computedAt,
        roundLegs: PositionLegs = legs,
        hasPosition: Boolean = true,
        unrecovered: Boolean = false,
        source: ForeignPerpPositionSource = manual,
    ) = AccountRound(
        roundLegs,
        source,
        BigDecimal("3.2"),
        if (hasPosition) pnl(at, unrecovered) else null,
        at,
    )

    private fun decide(state: AccountAlertState, round: AccountRound?) =
        AccountAlertPolicy.decide(state, round, settings, zone)

    @Test
    fun `실패 회차는 상태를 그대로 두고 알림이 없다`() {
        val state = AccountAlertState(
            PositionWatchState(legs, true),
            SwingState(bucket, BigDecimal("1.23"), true, false),
            ReminderState(bucket, BigDecimal("3.2"), bucket),
        )

        val decision = decide(state, null)

        assertThat(decision.next).isEqualTo(state)
        assertThat(decision.alerts).isEmpty()
    }

    @Test
    fun `첫 기동 회차는 알림 없이 legs 기준선 펀딩 추적을 채운다`() {
        val round = round()

        val decision = decide(AccountAlertState.INITIAL, round)

        assertThat(decision.alerts).isEmpty()
        assertThat(decision.next.position).isEqualTo(PositionWatchState(legs, false))
        assertThat(decision.next.swing).isEqualTo(SwingState(bucket, round.pnl!!.netPercent, false, false))
        assertThat(decision.next.reminder.manualFundingUsdt).isEqualByComparingTo("3.2")
        assertThat(decision.next.reminder.manualFundingChangedAt).isEqualTo(computedAt)
        assertThat(decision.next.reminder.lastDayStart).isNull()
    }

    @Test
    fun `빗썸만 바뀐 수동 회차는 경고 포함 포지션 알림 하나이고 급변 기준선을 비운다`() {
        val state = AccountAlertState(
            PositionWatchState(legs, false),
            SwingState(bucket, BigDecimal("0.10"), false, false),
            ReminderState.INITIAL,
        )

        val decision = decide(state, round(roundLegs = otherLegs))

        assertThat(decision.alerts).hasSize(1)
        val alert = decision.alerts.single() as AccountAlert.PositionChanged
        assertThat(alert.hedgeMismatchWarning).isTrue()
        assertThat(decision.next.swing).isEqualTo(SwingState(bucket, null, false, false))
        assertThat(decision.next.position).isEqualTo(PositionWatchState(otherLegs, true))
    }

    @Test
    fun `급변과 리마인더가 겹치면 급변 다음 리마인더 순서로 두 건이다`() {
        val current = pnl(computedAt, unrecovered = true).netPercent
        val state = AccountAlertState(
            PositionWatchState(legs, false),
            SwingState(bucket, current.subtract(BigDecimal.ONE), false, false),
            ReminderState.INITIAL,
        )

        val decision = decide(state, round(unrecovered = true))

        assertThat(decision.alerts.map { it.kind })
            .containsExactly(OwnerAlertKind.NET_PERCENT_SWING, OwnerAlertKind.UNRECOVERED_REMINDER)
        assertThat(decision.next.swing).isEqualTo(SwingState(bucket, state.swing.baselinePercent, true, false))
        assertThat(decision.next.reminder.lastDayStart).isEqualTo(Instant.parse("2026-10-19T15:00:00Z"))
    }

    @Test
    fun `포지션 변경과 리마인더가 겹치면 변경 다음 리마인더 순서이고 급변은 억제된다`() {
        val current = pnl(computedAt, unrecovered = true).netPercent
        val state = AccountAlertState(
            PositionWatchState(legs, true),
            SwingState(bucket, current.subtract(BigDecimal.ONE), false, false),
            ReminderState.INITIAL,
        )

        val decision = decide(state, round(roundLegs = otherLegs, unrecovered = true))

        assertThat(decision.alerts.map { it.kind })
            .containsExactly(OwnerAlertKind.POSITION_CHANGED, OwnerAlertKind.UNRECOVERED_REMINDER)
        assertThat(decision.next.swing).isEqualTo(SwingState(bucket, null, false, false))
    }

    @Test
    fun `포지션 없음 회차는 리마인더 lastDayStart 를 바꾸지 않는다`() {
        val sent = Instant.parse("2026-10-18T15:00:00Z")
        val state = AccountAlertState(
            PositionWatchState(legs, false),
            SwingState.INITIAL,
            ReminderState(sent, BigDecimal("3.2"), bucket),
        )

        val decision = decide(state, round(hasPosition = false))

        assertThat(decision.alerts).isEmpty()
        assertThat(decision.next.reminder.lastDayStart).isEqualTo(sent)
    }

    @Test
    fun `포지션 없음 회차도 leg 청산은 포지션 알림으로 판정하고 리마인더 날짜는 유지한다`() {
        val sent = Instant.parse("2026-10-18T15:00:00Z")
        val closed = PositionLegs(KoreaLeg.EMPTY, legs.foreign)
        val state = AccountAlertState(
            PositionWatchState(legs, false),
            SwingState.INITIAL,
            ReminderState(sent, BigDecimal("3.2"), bucket),
        )

        val decision = decide(state, round(roundLegs = closed, hasPosition = false))

        assertThat(decision.alerts).hasSize(1)
        val alert = decision.alerts.single() as AccountAlert.PositionChanged
        assertThat(alert.before).isEqualTo(legs)
        assertThat(alert.after).isEqualTo(closed)
        assertThat(decision.next.position.legs).isEqualTo(closed)
        assertThat(decision.next.reminder.lastDayStart).isEqualTo(sent)
    }
}
