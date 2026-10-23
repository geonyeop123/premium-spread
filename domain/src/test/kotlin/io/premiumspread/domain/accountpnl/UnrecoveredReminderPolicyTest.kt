package io.premiumspread.domain.accountpnl

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId

class UnrecoveredReminderPolicyTest {

    private val zone = ZoneId.of("Asia/Seoul")
    private val settings = AccountAlertSettings()
    private val t0 = Instant.parse("2026-10-19T00:00:00Z")
    private val day19 = Instant.parse("2026-10-19T15:00:00Z")

    private val legs = PositionLegs(
        KoreaLeg.of(BigDecimal("0.5"), BigDecimal("100000000")),
        ForeignLeg.of(BigDecimal("-0.5"), BigDecimal("70000")),
    )

    private fun position(koreaFee: Boolean, foreignFee: Boolean, funding: Boolean): AccountHedgePosition {
        val korea = KoreaSpotAccountSnapshot(
            quantity = BigDecimal("0.5"),
            averageBuyPrice = BigDecimal("100000000"),
            averageBuyPriceModified = false,
            entryFeeKrw = if (koreaFee) null else BigDecimal("12000"),
            sellFeeRate = BigDecimal("0.0004"),
            bidLevels = listOf(PriceLevel(BigDecimal("101000000"), BigDecimal("1.0"))),
            lastPrice = BigDecimal("101000000"),
            bidObservedAt = t0,
        )
        val foreign = ForeignPerpAccountSnapshot(
            signedQuantity = BigDecimal("-0.5"),
            entryPrice = BigDecimal("70000"),
            entryCommissionUsdt = if (foreignFee) null else BigDecimal("8.9"),
            fundingUsdt = if (funding) null else BigDecimal("3.2"),
            takerFeeRate = BigDecimal("0.0005"),
            askLevels = listOf(PriceLevel(BigDecimal("69000"), BigDecimal("5.0"))),
            lastPrice = BigDecimal("69000"),
            askObservedAt = t0,
        )
        return AccountHedgePosition(korea, foreign)
    }

    private fun round(
        at: String,
        koreaFee: Boolean = false,
        foreignFee: Boolean = false,
        funding: Boolean = false,
        source: ForeignPerpPositionSource = ForeignPerpPositionSource.EXCHANGE_API,
        manualFunding: String? = null,
        noPosition: Boolean = false,
    ): AccountRound {
        val observedAt = Instant.parse(at)
        val pnl = if (noPosition) {
            null
        } else {
            AccountNetPnl.calculate(position(koreaFee, foreignFee, funding), BigDecimal("1328.5"), observedAt, observedAt)
        }
        return AccountRound(legs, source, manualFunding?.let { BigDecimal(it) }, pnl, observedAt)
    }

    private fun decide(state: ReminderState, round: AccountRound, s: AccountAlertSettings = settings) =
        UnrecoveredReminderPolicy.decide(state, round, s, zone)

    @Test
    fun `dayStart 는 zone 날짜의 자정이다`() {
        assertThat(UnrecoveredReminderPolicy.dayStart(Instant.parse("2026-10-19T14:59:59Z"), zone))
            .isEqualTo(Instant.parse("2026-10-18T15:00:00Z"))
        assertThat(UnrecoveredReminderPolicy.dayStart(Instant.parse("2026-10-19T15:00:00Z"), zone)).isEqualTo(day19)
    }

    @Test
    fun `펀딩 추적은 출처와 값 변화에 따라 갱신된다`() {
        val manual = ForeignPerpPositionSource.MANUAL_ENV
        val seen = Instant.parse("2026-10-20T00:00:00Z")

        val api = decide(ReminderState(null, BigDecimal("3.2"), seen), round("2026-10-20T01:00:00Z", funding = true))
        assertThat(api.next.manualFundingUsdt).isNull()
        assertThat(api.next.manualFundingChangedAt).isNull()

        val first = decide(ReminderState.INITIAL, round("2026-10-20T01:00:00Z", source = manual, manualFunding = "3.2"))
        assertThat(first.next.manualFundingUsdt).isEqualByComparingTo(BigDecimal("3.2"))
        assertThat(first.next.manualFundingChangedAt).isEqualTo(Instant.parse("2026-10-20T01:00:00Z"))

        val same = decide(first.next, round("2026-10-20T02:00:00Z", source = manual, manualFunding = "3.20"))
        assertThat(same.next).isEqualTo(first.next)

        val changed = decide(first.next, round("2026-10-20T03:00:00Z", source = manual, manualFunding = "4.0"))
        assertThat(changed.next.manualFundingUsdt).isEqualByComparingTo(BigDecimal("4"))
        assertThat(changed.next.manualFundingUsdt.toString()).isEqualTo("4")
        assertThat(changed.next.manualFundingChangedAt).isEqualTo(Instant.parse("2026-10-20T03:00:00Z"))

        val cleared = decide(first.next, round("2026-10-20T03:00:00Z", source = manual, funding = true))
        assertThat(cleared.next.manualFundingUsdt).isNull()
        assertThat(cleared.next.manualFundingChangedAt).isEqualTo(Instant.parse("2026-10-20T03:00:00Z"))

        val toValue = decide(cleared.next, round("2026-10-20T04:00:00Z", source = manual, manualFunding = "3.2"))
        assertThat(toValue.next.manualFundingUsdt).isEqualByComparingTo(BigDecimal("3.2"))
        assertThat(toValue.next.manualFundingChangedAt).isEqualTo(Instant.parse("2026-10-20T04:00:00Z"))
    }

    @Test
    fun `설정 시각 경계와 하루 한 번 소비를 검증한다`() {
        val s0 = ReminderState.INITIAL

        assertThat(decide(s0, round("2026-10-19T23:59:59Z", koreaFee = true)).alert).isNull()

        val sent = decide(s0, round("2026-10-20T00:00:00Z", koreaFee = true))
        assertThat(sent.alert).isNotNull()
        assertThat(sent.next.lastDayStart).isEqualTo(day19)

        assertThat(decide(sent.next, round("2026-10-20T05:00:00Z", koreaFee = true)).alert).isNull()
        assertThat(decide(sent.next, round("2026-10-20T15:00:00Z", koreaFee = true)).alert).isNull()

        val next = decide(sent.next, round("2026-10-21T00:00:00Z", koreaFee = true))
        assertThat(next.alert).isNotNull()
        assertThat(next.next.lastDayStart).isEqualTo(Instant.parse("2026-10-20T15:00:00Z"))
    }

    @Test
    fun `항목은 선언 순서이며 해당하는 것만 담는다`() {
        val all = decide(ReminderState.INITIAL, round("2026-10-20T00:00:00Z", koreaFee = true, foreignFee = true, funding = true))
        assertThat(all.alert!!.items).containsExactly(
            ReminderItem.KOREA_ENTRY_FEE,
            ReminderItem.FOREIGN_ENTRY_FEE,
            ReminderItem.FUNDING,
        )
        assertThat(all.alert!!.dayStart).isEqualTo(day19)
        assertThat(all.alert!!.detectedAt).isEqualTo(Instant.parse("2026-10-20T00:00:00Z"))
        assertThat(all.alert!!.reminderHour).isEqualTo(9)

        val one = decide(ReminderState.INITIAL, round("2026-10-20T00:00:00Z", koreaFee = true))
        assertThat(one.alert!!.items).containsExactly(ReminderItem.KOREA_ENTRY_FEE)
    }

    @Test
    fun `수동 펀딩 미갱신은 경과 기간 경계를 포함한다`() {
        val manual = ForeignPerpPositionSource.MANUAL_ENV
        val judged = round("2026-10-20T00:00:00Z", source = manual, manualFunding = "3.2")

        val early = decide(ReminderState(null, BigDecimal("3.2"), Instant.parse("2026-10-17T00:00:00.001Z")), judged)
        assertThat(early.alert).isNull()

        val due = decide(ReminderState(null, BigDecimal("3.2"), Instant.parse("2026-10-17T00:00:00Z")), judged)
        assertThat(due.alert!!.items).containsExactly(ReminderItem.MANUAL_FUNDING_STALE)
        assertThat(due.alert!!.manualFunding!!.days).isEqualTo(3)
        assertThat(due.alert!!.manualFunding!!.fundingUsdt).isEqualByComparingTo(BigDecimal("3.2"))
        assertThat(due.alert!!.manualFunding!!.changedAt).isEqualTo(Instant.parse("2026-10-17T00:00:00Z"))
        assertThat(due.alert!!.source).isEqualTo(manual)
    }

    @Test
    fun `항목이 처음 생긴 회차에 보내고 같은 날 두 번째 항목은 보내지 않는다`() {
        var state = ReminderState.INITIAL
        for (at in listOf("2026-10-20T00:00:00Z", "2026-10-20T03:00:00Z")) {
            val d = decide(state, round(at))
            assertThat(d.alert).isNull()
            assertThat(d.next.lastDayStart).isNull()
            state = d.next
        }

        val sent = decide(state, round("2026-10-20T06:00:00Z", koreaFee = true))
        assertThat(sent.alert!!.items).containsExactly(ReminderItem.KOREA_ENTRY_FEE)
        assertThat(sent.next.lastDayStart).isEqualTo(day19)

        val later = decide(sent.next, round("2026-10-20T08:00:00Z", koreaFee = true, funding = true))
        assertThat(later.alert).isNull()
        assertThat(later.next.lastDayStart).isEqualTo(day19)
    }

    @Test
    fun `종일 항목이 없으면 아무것도 보내지 않는다`() {
        var state = ReminderState.INITIAL
        var sent = 0
        for (at in listOf("2026-10-20T00:00:00Z", "2026-10-20T04:30:00Z", "2026-10-20T09:00:00Z", "2026-10-20T14:59:00Z")) {
            val d = decide(state, round(at))
            if (d.alert != null) sent++
            state = d.next
        }
        assertThat(sent).isEqualTo(0)
        assertThat(state.lastDayStart).isNull()
    }

    @Test
    fun `포지션 없음 회차는 판정하지도 소비하지도 않는다`() {
        val manual = ForeignPerpPositionSource.MANUAL_ENV
        val none = decide(
            ReminderState.INITIAL,
            round("2026-10-20T01:00:00Z", koreaFee = true, source = manual, manualFunding = "3.2", noPosition = true),
        )
        assertThat(none.alert).isNull()
        assertThat(none.next.lastDayStart).isNull()
        assertThat(none.next.manualFundingUsdt).isEqualByComparingTo(BigDecimal("3.2"))
        assertThat(none.next.manualFundingChangedAt).isEqualTo(Instant.parse("2026-10-20T01:00:00Z"))

        val computed = decide(none.next, round("2026-10-20T01:01:00Z", koreaFee = true, source = manual, manualFunding = "3.2"))
        assertThat(computed.alert!!.items).containsExactly(ReminderItem.KOREA_ENTRY_FEE)
        assertThat(computed.next.lastDayStart).isEqualTo(day19)
    }

    @Test
    fun `reminderHour 가 0 이면 자정부터 판정한다`() {
        val midnight = AccountAlertSettings(reminderHour = 0)
        val d = decide(ReminderState.INITIAL, round("2026-10-20T15:00:00Z", koreaFee = true), midnight)
        assertThat(d.alert).isNotNull()
        assertThat(d.alert!!.reminderHour).isEqualTo(0)
        assertThat(d.next.lastDayStart).isEqualTo(Instant.parse("2026-10-20T15:00:00Z"))
    }
}
