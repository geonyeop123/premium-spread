package io.premiumspread.domain.accountpnl

import io.premiumspread.domain.accountpnl.AccountAlert.NetPercentSwing
import io.premiumspread.domain.accountpnl.AccountAlert.PositionChanged
import io.premiumspread.domain.accountpnl.AccountAlert.UnrecoveredReminder
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId

class AccountAlertContentTest {

    private val zone = ZoneId.of("Asia/Seoul")
    private val runbook = "조치 안내: docs/runbooks/account-pnl-alert.md"

    private fun korea(quantity: String, average: String) = KoreaLeg.of(BigDecimal(quantity), BigDecimal(average))
    private fun foreign(quantity: String, entry: String) = ForeignLeg.of(BigDecimal(quantity), BigDecimal(entry))

    private val bithumbChangedManual = PositionChanged(
        before = PositionLegs(korea("0.5", "100000000"), foreign("-0.5", "70000")),
        after = PositionLegs(korea("0.6", "101000000"), foreign("-0.5", "70000")),
        hedgeMismatchWarning = true,
        source = ForeignPerpPositionSource.MANUAL_ENV,
        detectedAt = Instant.parse("2026-10-20T03:00:00Z"),
    )

    private val binanceClosedApi = PositionChanged(
        before = PositionLegs(korea("0.6", "101000000"), foreign("-0.5", "70000")),
        after = PositionLegs(korea("0.6", "101000000"), ForeignLeg.FLAT),
        hedgeMismatchWarning = false,
        source = ForeignPerpPositionSource.EXCHANGE_API,
        detectedAt = Instant.parse("2026-10-20T03:00:00Z"),
    )

    private fun pnl(netPercent: String, netKrw: String, atLast: String) = AccountNetPnl(
        grossKrw = BigDecimal("500000.5"),
        entryFeeKrw = BigDecimal("23823.65"),
        fundingKrw = BigDecimal("4251.2"),
        exitFeeEstimateKrw = BigDecimal("15000.1"),
        netKrw = BigDecimal(netKrw),
        netPercent = BigDecimal(netPercent),
        grossKrwAtLast = BigDecimal("490000"),
        exitFeeEstimateKrwAtLast = BigDecimal("15000"),
        netKrwAtLast = BigDecimal("455000"),
        netPercentAtLast = BigDecimal(atLast),
        koreaExitPrice = BigDecimal("109500000"),
        foreignExitPrice = BigDecimal("81580"),
        koreaEntryFeeUnrecovered = false,
        foreignEntryFeeUnrecovered = false,
        fundingUnrecovered = false,
        position = AccountHedgePosition(
            KoreaSpotAccountSnapshot(
                quantity = BigDecimal("0.266"),
                averageBuyPrice = BigDecimal("113016278"),
                averageBuyPriceModified = false,
                entryFeeKrw = BigDecimal("12000"),
                sellFeeRate = BigDecimal("0.0004"),
                bidLevels = listOf(PriceLevel(BigDecimal("109500000"), BigDecimal("1.0"))),
                lastPrice = BigDecimal("109575000"),
                bidObservedAt = Instant.parse("2026-10-20T03:00:00Z"),
            ),
            ForeignPerpAccountSnapshot(
                signedQuantity = BigDecimal("-0.266"),
                entryPrice = BigDecimal("83549.4"),
                entryCommissionUsdt = BigDecimal("8.9"),
                fundingUsdt = BigDecimal("3.2"),
                takerFeeRate = BigDecimal("0.0005"),
                askLevels = listOf(PriceLevel(BigDecimal("81580"), BigDecimal("5.0"))),
                lastPrice = BigDecimal("81577"),
                askObservedAt = Instant.parse("2026-10-20T03:00:01Z"),
            ),
        ),
        fxRate = BigDecimal("1328.5"),
        fxObservedAt = Instant.parse("2026-10-20T02:30:00Z"),
        calculatedAt = Instant.parse("2026-10-20T03:00:05Z"),
    )

    private val swingUp = NetPercentSwing(
        direction = SwingDirection.UP,
        bucketStart = Instant.parse("2026-10-20T03:00:00Z"),
        baselinePercent = BigDecimal("0.40"),
        threshold = BigDecimal("0.50"),
        pnl = pnl("0.90", "123456.6", "0.85"),
    )

    private val swingDown = NetPercentSwing(
        direction = SwingDirection.DOWN,
        bucketStart = Instant.parse("2026-10-20T03:00:00Z"),
        baselinePercent = BigDecimal("0.40"),
        threshold = BigDecimal("0.50"),
        pnl = pnl("-0.10", "-12345.4", "-0.12"),
    )

    private val reminderFour = UnrecoveredReminder(
        dayStart = Instant.parse("2026-10-19T15:00:00Z"),
        items = listOf(
            ReminderItem.KOREA_ENTRY_FEE,
            ReminderItem.FOREIGN_ENTRY_FEE,
            ReminderItem.FUNDING,
            ReminderItem.MANUAL_FUNDING_STALE,
        ),
        source = ForeignPerpPositionSource.MANUAL_ENV,
        manualFunding = ManualFundingStaleness(BigDecimal("3.2"), Instant.parse("2026-10-17T00:30:00Z"), 3),
        reminderHour = 9,
        detectedAt = Instant.parse("2026-10-20T00:30:00Z"),
    )

    private val reminderOne = UnrecoveredReminder(
        dayStart = Instant.parse("2026-10-19T15:00:00Z"),
        items = listOf(ReminderItem.KOREA_ENTRY_FEE),
        source = ForeignPerpPositionSource.EXCHANGE_API,
        manualFunding = null,
        reminderHour = 9,
        detectedAt = Instant.parse("2026-10-20T06:00:00Z"),
    )

    @Test
    fun `포지션 변경 메일 - 빗썸만 manual 경고`() {
        val mail = AccountAlertContent.mail(bithumbChangedManual, zone)

        assertThat(mail.eventKey).isEqualTo("account-pnl-position-1792465200000")
        assertThat(mail.subject).isEqualTo("[김프 알림] 포지션 변경 — 빗썸 · 헤지 불일치 확인")
        assertThat(mail.body).isEqualTo(
            listOf(
                "실계정 포지션 변경을 감지했습니다.",
                "빗썸 현물: 0.5 BTC · 평단 100,000,000원 → 0.6 BTC · 평단 101,000,000원",
                "바이낸스 선물(변경 없음): 숏 0.5 BTC · 진입가 70000 USDT",
                "바이낸스 포지션 출처: 수동 입력(env)",
                "감지 시각: 2026-10-20 12:00:00 (2026-10-20T03:00:00Z)",
                "",
                "⚠ 바이낸스 env 미갱신 — 헤지 불일치",
                "빗썸 포지션만 바뀌었고 바이낸스 포지션은 수동 입력(env) 값 그대로입니다. 바이낸스 포지션도 바꿨다면 " +
                    "BINANCE_MANUAL_SHORT_QUANTITY·BINANCE_MANUAL_ENTRY_PRICE 를 고치고 batch 를 재시작하세요. " +
                    "이 경고는 바이낸스 값이 바뀔 때까지 다시 보내지 않습니다.",
                "",
                "순수익률 급변 기준선은 다음 정시 첫 성공 회차에 다시 잡습니다.",
                runbook,
            ).joinToString("\n"),
        )
    }

    @Test
    fun `포지션 변경 메일 - 바이낸스만 API 청산`() {
        val mail = AccountAlertContent.mail(binanceClosedApi, zone)

        assertThat(mail.subject).isEqualTo("[김프 알림] 포지션 변경 — 바이낸스")
        assertThat(mail.body).isEqualTo(
            listOf(
                "실계정 포지션 변경을 감지했습니다.",
                "빗썸 현물(변경 없음): 0.6 BTC · 평단 101,000,000원",
                "바이낸스 선물: 숏 0.5 BTC · 진입가 70000 USDT → 없음",
                "바이낸스 포지션 출처: 거래소 API",
                "감지 시각: 2026-10-20 12:00:00 (2026-10-20T03:00:00Z)",
                "",
                "순수익률 급변 기준선은 다음 정시 첫 성공 회차에 다시 잡습니다.",
                runbook,
            ).joinToString("\n"),
        )
    }

    @Test
    fun `급변 상승 메일`() {
        val mail = AccountAlertContent.mail(swingUp, zone)

        assertThat(mail.eventKey).isEqualTo("account-pnl-swing-up-1792465200000")
        assertThat(mail.subject).isEqualTo("[김프 알림] 순수익률 급변 ▲ 상승 +0.50%p · 현재 +0.90%")
        assertThat(mail.body).isEqualTo(
            listOf(
                "순수익률이 이번 시간 기준선에서 0.50%p 이상 올랐습니다.",
                "기준선(12:00 시간대 첫 성공 회차): +0.40%",
                "현재: +0.90% (변화 +0.50%p)",
                "순손익: +123,457원 · 체결가 기준 +0.85%",
                "계산 시각: 2026-10-20 12:00:05 (2026-10-20T03:00:05Z)",
                "",
                "같은 시간대 안에서 같은 방향은 한 번만 알립니다. 기준선은 매시 첫 성공 회차에 다시 잡습니다.",
            ).joinToString("\n"),
        )
    }

    @Test
    fun `급변 하락 메일`() {
        val mail = AccountAlertContent.mail(swingDown, zone)

        assertThat(mail.eventKey).isEqualTo("account-pnl-swing-down-1792465200000")
        assertThat(mail.subject).isEqualTo("[김프 알림] 순수익률 급변 ▼ 하락 -0.50%p · 현재 -0.10%")
        assertThat(mail.body).isEqualTo(
            listOf(
                "순수익률이 이번 시간 기준선에서 0.50%p 이상 내렸습니다.",
                "기준선(12:00 시간대 첫 성공 회차): +0.40%",
                "현재: -0.10% (변화 -0.50%p)",
                "순손익: -12,345원 · 체결가 기준 -0.12%",
                "계산 시각: 2026-10-20 12:00:05 (2026-10-20T03:00:05Z)",
                "",
                "같은 시간대 안에서 같은 방향은 한 번만 알립니다. 기준선은 매시 첫 성공 회차에 다시 잡습니다.",
            ).joinToString("\n"),
        )
    }

    @Test
    fun `리마인더 메일 4항목`() {
        val mail = AccountAlertContent.mail(reminderFour, zone)

        assertThat(mail.eventKey).isEqualTo("account-pnl-reminder-1792422000000")
        assertThat(mail.subject).isEqualTo("[김프 알림] 미반영 항목 리마인더 2026-10-20 · 4건")
        assertThat(mail.body).isEqualTo(
            listOf(
                "아래 항목이 순손익 계산에 반영되지 않았거나 오래 갱신되지 않았습니다.",
                "- 진입 수수료(빗썸) 미반영 — 이력 없음",
                "- 진입 수수료(바이낸스) 미반영 — 수동 미입력",
                "- 펀딩비 미반영 — 수동 미입력",
                "- 수동 펀딩비 3일째 미갱신 — 현재 값 +3.2 USDT, 마지막 변경 2026-10-17 09:30:00 (2026-10-17T00:30:00Z)",
                "",
                "하루 한 번, 09:00 이후 항목이 처음 확인된 회차에 보냅니다. $runbook",
            ).joinToString("\n"),
        )
    }

    @Test
    fun `리마인더 메일 1항목`() {
        val mail = AccountAlertContent.mail(reminderOne, zone)

        assertThat(mail.subject).isEqualTo("[김프 알림] 미반영 항목 리마인더 2026-10-20 · 1건")
        assertThat(mail.body).isEqualTo(
            listOf(
                "아래 항목이 순손익 계산에 반영되지 않았거나 오래 갱신되지 않았습니다.",
                "- 진입 수수료(빗썸) 미반영 — 이력 없음",
                "",
                "하루 한 번, 09:00 이후 항목이 처음 확인된 회차에 보냅니다. $runbook",
            ).joinToString("\n"),
        )
    }

    @Test
    fun `포지션 변경 Discord - 빗썸만 manual 경고`() {
        val message = AccountAlertContent.discord(bithumbChangedManual, zone)

        assertThat(message.title).isEqualTo("⚠️ 포지션 변경 — 빗썸 · 헤지 불일치")
        assertThat(message.description).isEqualTo(
            listOf(
                "빗썸 0.5 BTC · 평단 100,000,000원 → 0.6 BTC · 평단 101,000,000원",
                "바이낸스 숏 0.5 BTC · 진입가 70000 USDT (변경 없음)",
                "⚠ 바이낸스 env 미갱신 — 헤지 불일치. BINANCE_MANUAL_SHORT_QUANTITY·BINANCE_MANUAL_ENTRY_PRICE 갱신 후 재시작",
                "바이낸스 포지션: 수동 입력(env) · 2026-10-20 12:00 KST",
            ).joinToString("\n"),
        )
        assertThat(message.color).isEqualTo(15105570)
    }

    @Test
    fun `포지션 변경 Discord - 바이낸스만 API 청산`() {
        val message = AccountAlertContent.discord(binanceClosedApi, zone)

        assertThat(message.title).isEqualTo("🔄 포지션 변경 — 바이낸스")
        assertThat(message.description).isEqualTo(
            listOf(
                "빗썸 0.6 BTC · 평단 101,000,000원 (변경 없음)",
                "바이낸스 숏 0.5 BTC · 진입가 70000 USDT → 없음",
                "바이낸스 포지션: 거래소 API · 2026-10-20 12:00 KST",
            ).joinToString("\n"),
        )
        assertThat(message.color).isEqualTo(15844367)
    }

    @Test
    fun `급변 Discord 상승과 하락`() {
        val up = AccountAlertContent.discord(swingUp, zone)
        val down = AccountAlertContent.discord(swingDown, zone)

        assertThat(up.title).isEqualTo("📈 순수익률 급등 +0.50%p")
        assertThat(up.description).isEqualTo(
            listOf(
                "12:00 기준 +0.40% → 현재 +0.90%",
                "순손익 +123,457원 · 체결가 기준 +0.85%",
                "임계값 ±0.50%p · 2026-10-20 12:00 KST",
            ).joinToString("\n"),
        )
        assertThat(up.color).isEqualTo(1752220)
        assertThat(down.title).isEqualTo("📉 순수익률 급락 -0.50%p")
        assertThat(down.description).isEqualTo(
            listOf(
                "12:00 기준 +0.40% → 현재 -0.10%",
                "순손익 -12,345원 · 체결가 기준 -0.12%",
                "임계값 ±0.50%p · 2026-10-20 12:00 KST",
            ).joinToString("\n"),
        )
        assertThat(down.color).isEqualTo(15277667)
    }

    @Test
    fun `리마인더 Discord 4항목과 1항목`() {
        val four = AccountAlertContent.discord(reminderFour, zone)
        val one = AccountAlertContent.discord(reminderOne, zone)

        assertThat(four.title).isEqualTo("🗒️ 미반영 리마인더 · 4건")
        assertThat(four.description).isEqualTo(
            listOf(
                "• 진입 수수료(빗썸) 미반영 — 이력 없음",
                "• 진입 수수료(바이낸스) 미반영 — 수동 미입력",
                "• 펀딩비 미반영 — 수동 미입력",
                "• 수동 펀딩비 3일째 미갱신 — 현재 값 +3.2 USDT, 마지막 변경 2026-10-17 09:30:00 (2026-10-17T00:30:00Z)",
                "바이낸스 포지션: 수동 입력(env) · 2026-10-20 09:30 KST",
            ).joinToString("\n"),
        )
        assertThat(four.color).isEqualTo(8359053)
        assertThat(one.title).isEqualTo("🗒️ 미반영 리마인더 · 1건")
        assertThat(one.description).isEqualTo(
            listOf(
                "• 진입 수수료(빗썸) 미반영 — 이력 없음",
                "바이낸스 포지션: 거래소 API · 2026-10-20 15:00 KST",
            ).joinToString("\n"),
        )
    }

    @Test
    fun `색 상수 5개 값`() {
        assertThat(AccountAlertContent.COLOR_POSITION_CHANGED).isEqualTo(15844367)
        assertThat(AccountAlertContent.COLOR_HEDGE_MISMATCH).isEqualTo(15105570)
        assertThat(AccountAlertContent.COLOR_SWING_UP).isEqualTo(1752220)
        assertThat(AccountAlertContent.COLOR_SWING_DOWN).isEqualTo(15277667)
        assertThat(AccountAlertContent.COLOR_REMINDER).isEqualTo(8359053)
    }

    @Test
    fun `eventKey 는 4종 형식이고 Message-ID 규칙에 맞는다`() {
        val keys = listOf(bithumbChangedManual, swingUp, swingDown, reminderFour).map(AccountAlertContent::eventKey)

        assertThat(keys).containsExactly(
            "account-pnl-position-1792465200000",
            "account-pnl-swing-up-1792465200000",
            "account-pnl-swing-down-1792465200000",
            "account-pnl-reminder-1792422000000",
        )
        assertThat(keys).allSatisfy { assertThat(it).matches("[A-Za-z0-9._-]{1,128}") }
    }

    @Test
    fun `모든 결과에 주소 webhook env 원문이 없다`() {
        val alerts = listOf(bithumbChangedManual, binanceClosedApi, swingUp, swingDown, reminderFour, reminderOne)
        val texts = alerts.flatMap {
            val mail = AccountAlertContent.mail(it, zone)
            val discord = AccountAlertContent.discord(it, zone)
            listOf(mail.subject, mail.body, discord.title, discord.description)
        }

        assertThat(texts).allSatisfy {
            assertThat(it).doesNotContain("example.test", "/api/webhooks/", "BINANCE_MANUAL_FUNDING_USDT=")
        }
    }

    @Test
    fun `percentPoint 와 date`() {
        assertThat(AccountPnlFormat.percentPoint(BigDecimal("0.5"))).isEqualTo("+0.50%p")
        assertThat(AccountPnlFormat.percentPoint(BigDecimal("-0.5"))).isEqualTo("-0.50%p")
        assertThat(AccountPnlFormat.date(Instant.parse("2026-10-19T15:00:00Z"), zone)).isEqualTo("2026-10-20")
    }
}
