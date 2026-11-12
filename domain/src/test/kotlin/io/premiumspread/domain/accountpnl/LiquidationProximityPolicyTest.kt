package io.premiumspread.domain.accountpnl

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

class LiquidationProximityPolicyTest {

    private val at = Instant.parse("2026-10-20T03:00:00Z")
    private val config = ForeignPerpSymbolConfig(5, ForeignPerpMarginType.ISOLATED)

    private fun risk(mark: String, liq: String?) = ForeignPerpPositionRisk(
        liquidationPrice = liq?.let { BigDecimal(it) },
        markPrice = BigDecimal(mark),
        isolatedMargin = BigDecimal("100"),
        isolatedWallet = BigDecimal("100"),
        maintMargin = BigDecimal("1"),
        positionInitialMargin = BigDecimal("100"),
        notional = BigDecimal("500"),
    )

    private fun round(
        quantity: String,
        entry: String,
        risk: ForeignPerpPositionRisk?,
        source: ForeignPerpPositionSource = ForeignPerpPositionSource.EXCHANGE_API,
    ) = AccountRound(
        legs = PositionLegs(KoreaLeg.EMPTY, ForeignLeg.of(BigDecimal(quantity), BigDecimal(entry))),
        source = source,
        fundingUsdt = null,
        pnl = null,
        observedAt = at,
        foreignRisk = risk,
        foreignConfig = config,
    )

    /** 숏 entry 100 · liq 200 — 진행률 = mark − 100. */
    private fun short(progress: String, entry: String = "100", liq: String = "200"): AccountRound {
        val mark = BigDecimal(entry).add(
            BigDecimal(progress).multiply(BigDecimal(liq).subtract(BigDecimal(entry))).divide(BigDecimal(100)),
        )
        return round("-0.5", entry, risk(mark.toPlainString(), liq))
    }

    private fun flat() = round("0", "0", null)

    /** 진입가는 [ForeignLeg.of] 처럼 stripTrailingZeros 해서 data class 비교가 맞게 한다. */
    private fun state(side: PerpSide, entry: String, level: Int) =
        LiquidationProximityState(side, BigDecimal(entry).stripTrailingZeros(), level)

    /** 회차를 차례로 넣어 (마지막 상태, 알림 목록) 을 돌려준다. */
    private fun run(
        rounds: List<AccountRound?>,
        start: LiquidationProximityState = LiquidationProximityState.INITIAL,
    ): Pair<LiquidationProximityState, List<AccountAlert>> {
        var state = start
        val alerts = mutableListOf<AccountAlert>()
        rounds.forEach {
            val decision = LiquidationProximityPolicy.decide(state, it)
            state = decision.next
            decision.alert?.let(alerts::add)
        }
        return state to alerts
    }

    private fun List<AccountAlert>.upLevels() = filterIsInstance<AccountAlert.LiquidationProximity>().map { it.level }

    private fun List<AccountAlert>.eased() = filterIsInstance<AccountAlert.LiquidationEased>()

    @Test
    fun `45 55 65 95 는 50 60 90 세 건이다`() {
        val (last, alerts) = run(listOf(short("45"), short("55"), short("65"), short("95")))

        assertThat(alerts).hasSize(3)
        assertThat(alerts.upLevels()).containsExactly(50, 60, 90)
        assertThat(last).isEqualTo(state(PerpSide.SHORT, "100", 90))
        val first = alerts.first() as AccountAlert.LiquidationProximity
        assertThat(first.side).isEqualTo(PerpSide.SHORT)
        assertThat(first.progressPercent).isEqualByComparingTo("55")
        assertThat(first.entryPrice).isEqualByComparingTo("100")
        assertThat(first.markPrice).isEqualByComparingTo("155")
        assertThat(first.liquidationPrice).isEqualByComparingTo("200")
        assertThat(first.config).isEqualTo(config)
        assertThat(first.observedAt).isEqualTo(at)
    }

    @Test
    fun `진동은 재무장 전까지 다시 알리지 않는다`() {
        val (last, alerts) = run(listOf(short("55"), short("49"), short("55"), short("49"), short("55")))

        assertThat(alerts.upLevels()).containsExactly(50)
        assertThat(alerts.eased()).isEmpty()
        assertThat(last.level).isEqualTo(50)
    }

    @Test
    fun `한 단계 아래 경계 밑으로 내려가면 재무장한다`() {
        val (last, alerts) = run(listOf(short("55"), short("39"), short("55")))

        assertThat(alerts.upLevels()).containsExactly(50, 50)
        assertThat(last.level).isEqualTo(50)
    }

    @Test
    fun `숏 롱 진행률은 같은 식이다`() {
        assertThat(LiquidationProximityPolicy.progress(BigDecimal("100"), BigDecimal("160"), BigDecimal("200")))
            .isEqualByComparingTo("60")
        assertThat(LiquidationProximityPolicy.progress(BigDecimal("100"), BigDecimal("70"), BigDecimal("50")))
            .isEqualByComparingTo("60")

        val shortAlert = LiquidationProximityPolicy.decide(
            LiquidationProximityState.INITIAL,
            round("-0.5", "100", risk("160", "200")),
        ).alert as AccountAlert.LiquidationProximity
        val longDecision = LiquidationProximityPolicy.decide(
            LiquidationProximityState.INITIAL,
            round("0.5", "100", risk("70", "50")),
        )
        val longAlert = longDecision.alert as AccountAlert.LiquidationProximity

        assertThat(shortAlert.level).isEqualTo(60)
        assertThat(shortAlert.side).isEqualTo(PerpSide.SHORT)
        assertThat(longAlert.level).isEqualTo(60)
        assertThat(longAlert.side).isEqualTo(PerpSide.LONG)
        assertThat(longAlert.progressPercent).isEqualByComparingTo("60")
        assertThat(longDecision.next).isEqualTo(state(PerpSide.LONG, "100", 60))
    }

    @Test
    fun `유리한 쪽과 경계 직전은 알리지 않는다`() {
        val favorable = LiquidationProximityPolicy.decide(
            LiquidationProximityState.INITIAL,
            round("-0.5", "100", risk("90", "200")),
        )
        val almost = LiquidationProximityPolicy.decide(
            LiquidationProximityState.INITIAL,
            round("-0.5", "100", risk("149.99999", "200")),
        )

        assertThat(favorable.alert).isNull()
        assertThat(favorable.next).isEqualTo(state(PerpSide.SHORT, "100", 0))
        assertThat(LiquidationProximityPolicy.progress(BigDecimal("100"), BigDecimal("90"), BigDecimal("200")))
            .isEqualByComparingTo("-10")
        assertThat(almost.alert).isNull()
        assertThat(almost.next.level).isEqualTo(0)
        assertThat(LiquidationProximityPolicy.progress(BigDecimal("100"), BigDecimal("149.99999"), BigDecimal("200")))
            .isEqualByComparingTo("49.9999")
    }

    @Test
    fun `판정하지 않는 회차는 상태를 바꾸지 않는다`() {
        val before = state(PerpSide.SHORT, "100", 60)
        val rounds = listOf(
            null,
            round("-0.5", "100", risk("195", "200"), ForeignPerpPositionSource.MANUAL_ENV),
            round("0", "0", null, ForeignPerpPositionSource.MANUAL_ENV),
            round("-0.5", "100", null),
            round("-0.5", "100", risk("195", null)),
            round("-0.5", "100", risk("195", "100")),
            round("-0.5", "100", risk("195", "50")),
            round("0.5", "100", risk("40", "150")),
        )

        rounds.forEach {
            val decision = LiquidationProximityPolicy.decide(before, it)

            assertThat(decision.next).isEqualTo(before)
            assertThat(decision.alert).isNull()
        }
        assertThat(LiquidationProximityPolicy.progress(BigDecimal("100"), BigDecimal("195"), BigDecimal("100"))).isNull()
    }

    @Test
    fun `바이낸스 flat 이면 초기화한다`() {
        val (cleared, none) = run(listOf(flat()), state(PerpSide.SHORT, "100", 90))
        val (last, alerts) = run(listOf(short("55")), cleared)

        assertThat(cleared).isEqualTo(LiquidationProximityState.INITIAL)
        assertThat(none).isEmpty()
        assertThat(alerts.upLevels()).containsExactly(50)
        assertThat(last).isEqualTo(state(PerpSide.SHORT, "100", 50))
    }

    @Test
    fun `방향이 바뀌면 처음부터 판정한다`() {
        val decision = LiquidationProximityPolicy.decide(
            state(PerpSide.SHORT, "100", 90),
            round("0.5", "100", risk("72.5", "50")),
        )

        val alert = decision.alert as AccountAlert.LiquidationProximity
        assertThat(alert.level).isEqualTo(50)
        assertThat(alert.side).isEqualTo(PerpSide.LONG)
        assertThat(decision.next).isEqualTo(state(PerpSide.LONG, "100", 50))
    }

    @Test
    fun `진입가가 바뀌면 현재 경계를 다시 알린다`() {
        val decision = LiquidationProximityPolicy.decide(
            state(PerpSide.SHORT, "100", 90),
            short("85", entry = "110", liq = "210"),
        )
        val sameEntryScale = LiquidationProximityPolicy.decide(
            LiquidationProximityState(PerpSide.SHORT, BigDecimal("100.00"), 90),
            short("85"),
        )

        assertThat((decision.alert as AccountAlert.LiquidationProximity).level).isEqualTo(80)
        assertThat(decision.next).isEqualTo(state(PerpSide.SHORT, "110", 80))
        assertThat(sameEntryScale.alert).isNull()
        assertThat(sameEntryScale.next.level).isEqualTo(90)
    }

    @Test
    fun `청산가만 바뀌면 단계를 유지한다`() {
        val decision = LiquidationProximityPolicy.decide(
            state(PerpSide.SHORT, "100", 60),
            round("-0.5", "100", risk("230", "300")),
        )

        assertThat(decision.alert).isNull()
        assertThat(decision.next).isEqualTo(state(PerpSide.SHORT, "100", 60))
    }

    @Test
    fun `알린 경계가 재무장되면 완화 알림 1건이다`() {
        val (afterEase, alerts) = run(listOf(short("55"), short("65"), short("45")))
        val (last, more) = run(listOf(short("61")), afterEase)

        assertThat(alerts).hasSize(3)
        val eased = alerts.last() as AccountAlert.LiquidationEased
        assertThat(eased.previousLevel).isEqualTo(60)
        assertThat(eased.armedLevel).isEqualTo(50)
        assertThat(eased.progressPercent).isEqualByComparingTo("45")
        assertThat(eased.side).isEqualTo(PerpSide.SHORT)
        assertThat(afterEase.level).isEqualTo(50)
        assertThat(more.upLevels()).containsExactly(60)
        assertThat(last.level).isEqualTo(60)

        val (_, multiple) = run(listOf(short("95"), short("10")))
        val drop = multiple.eased().single()
        assertThat(drop.previousLevel).isEqualTo(90)
        assertThat(drop.armedLevel).isEqualTo(0)

        val (_, vibrate) = run(listOf(short("55"), short("49"), short("55"), short("49")))
        assertThat(vibrate.eased()).isEmpty()
    }

    @Test
    fun `완화 알림을 보내지 않는 재무장`() {
        val stored = state(PerpSide.SHORT, "100", 90)

        val entryChanged = LiquidationProximityPolicy.decide(stored, short("30", entry = "110", liq = "210"))
        val flatReset = LiquidationProximityPolicy.decide(stored, flat())
        val sideChanged = LiquidationProximityPolicy.decide(stored, round("0.5", "100", risk("95", "50")))

        assertThat(entryChanged.alert).isNull()
        assertThat(entryChanged.next).isEqualTo(state(PerpSide.SHORT, "110", 0))
        assertThat(flatReset.alert).isNull()
        assertThat(flatReset.next).isEqualTo(LiquidationProximityState.INITIAL)
        assertThat(sideChanged.alert).isNull()
        assertThat(sideChanged.next).isEqualTo(state(PerpSide.LONG, "100", 0))
    }

    @Test
    fun `상태는 셋 다 비었거나 셋 다 있고 toString 에 진입가가 없다`() {
        assertThat(runCatching { LiquidationProximityState(PerpSide.SHORT, null, null) }.isFailure).isTrue()
        assertThat(runCatching { LiquidationProximityState(null, null, 50) }.isFailure).isTrue()
        assertThat(runCatching { state(PerpSide.SHORT, "100", 55) }.isFailure).isTrue()
        assertThat(state(PerpSide.SHORT, "123456.7", 60).toString())
            .isEqualTo("LiquidationProximityState(side=SHORT, level=60)")
    }

    @Test
    fun `알림 toString 에 가격이 없다`() {
        val alert = LiquidationProximityPolicy.decide(
            LiquidationProximityState.INITIAL,
            round("-0.5", "70123", risk("80123", "80456")),
        ).alert

        assertThat(alert.toString()).doesNotContain("70123").doesNotContain("80123").doesNotContain("80456")
    }
}
