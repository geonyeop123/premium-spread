package io.premiumspread.domain.accountpnl

import io.premiumspread.domain.accountpnl.AccountAlert.NetPercentSwing
import io.premiumspread.domain.accountpnl.AccountAlert.PositionChanged
import io.premiumspread.domain.accountpnl.AccountAlert.UnrecoveredReminder
import io.premiumspread.domain.alertmessage.EmbedCell
import io.premiumspread.domain.alertmessage.gf
import io.premiumspread.domain.alertmessage.OwnerAlertMessage
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId

class AccountAlertContentTest {

    private val zone = ZoneId.of("Asia/Seoul")
    private val warn = "⚠️"

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

    private fun msg(alert: AccountAlert): OwnerAlertMessage = AccountAlertContent.message(alert, zone)

    private fun cell(name: String, value: String) = EmbedCell(name, value)

    @Test
    fun `포지션 변경 바이낸스 청산 golden`() {
        val m = msg(binanceClosedApi)

        assertThat(m.eventKey).isEqualTo("account-pnl-position-1792465200000")
        assertThat(m.mail.subject).isEqualTo("[실계정] 포지션 변경 — 바이낸스")
        assertThat(m.mail.body()).isEqualTo(
            """
            실계정 포지션이 바뀌었습니다.

            ■ 빗썸 현물 (변경 없음)
            0.6 BTC · 평단 101,000,000원

            ■ 바이낸스 선물 숏
            변경 전  0.5 BTC · 평단 70,000 USDT
            변경 후  포지션 없음

            순수익률 급변 기준선은 다음 정시에 다시 잡습니다.

            기준 시각 2026-10-20 12:00 KST
            """.trimIndent(),
        )
        val e = m.discord
        assertThat(e.author).isEqualTo("실계정 · BTC")
        assertThat(e.title).isEqualTo("🔄 포지션 변경 — 바이낸스")
        assertThat(e.color).isEqualTo(0xF1C40F)
        assertThat(e.footer).isEqualTo("순수익률 급변 기준선은 다음 정시에 다시 잡습니다")
        assertThat(e.timestamp).isEqualTo(binanceClosedApi.detectedAt)
        assertThat(e.rows).containsExactly(
            listOf(
                cell("빗썸 현물 (변경 없음)", "0.6 BTC\n평단 101,000,000원"),
                cell("바이낸스 선물 숏", "0.5 BTC · 평단 70,000 USDT\n→ 포지션 없음"),
            ),
        )
        val layout = e.toDiscordMessage().layout!!
        assertThat(layout.fields).isEqualTo(
            listOf(
                gf("빗썸 현물 (변경 없음)", "0.6 BTC\n평단 101,000,000원", true),
                gf("바이낸스 선물 숏", "0.5 BTC · 평단 70,000 USDT\n→ 포지션 없음", true),
            ),
        )
        assertThat(layout.author).isEqualTo("실계정 · BTC")
        assertThat(layout.timestamp).isEqualTo(binanceClosedApi.detectedAt)
    }

    @Test
    fun `포지션 변경 헤지 불일치 golden`() {
        val m = msg(bithumbChangedManual)

        assertThat(m.mail.subject).isEqualTo("[실계정] 포지션 변경 — 빗썸 · 헤지 불일치")
        assertThat(m.mail.body()).isEqualTo(
            """
            실계정 포지션이 바뀌었습니다. 헤지가 맞지 않습니다.

            ■ 빗썸 현물
            변경 전  0.5 BTC · 평단 100,000,000원
            변경 후  0.6 BTC · 평단 101,000,000원

            ■ 바이낸스 선물 숏 (변경 없음)
            0.5 BTC · 평단 70,000 USDT

            ■ 확인할 것
            바이낸스는 수동 입력값 그대로입니다. 바이낸스 포지션도 바꿨다면
            BINANCE_MANUAL_SHORT_QUANTITY · BINANCE_MANUAL_ENTRY_PRICE를 고치고 batch를 재시작하세요.
            바이낸스 값이 바뀔 때까지 이 경고는 다시 보내지 않습니다.

            기준 시각 2026-10-20 12:00 KST
            """.trimIndent(),
        )
        val e = m.discord
        assertThat(e.author).isEqualTo("실계정 · BTC")
        assertThat(e.title).isEqualTo("$warn 포지션 변경 — 빗썸 · 헤지 불일치")
        assertThat(e.color).isEqualTo(0xE67E22)
        assertThat(e.footer).isEqualTo("바이낸스 값이 바뀔 때까지 이 경고는 다시 보내지 않습니다")
        assertThat(e.rows).containsExactly(
            listOf(
                cell("빗썸 현물", "0.5 BTC · 평단 100,000,000원\n→ 0.6 BTC · 평단 101,000,000원"),
                cell("바이낸스 선물 숏 (변경 없음)", "0.5 BTC · 평단 70,000 USDT"),
            ),
            listOf(
                cell(
                    "확인할 것",
                    "바이낸스는 수동 입력값 그대로입니다. 바이낸스 포지션도 바꿨다면 " +
                        "BINANCE_MANUAL_SHORT_QUANTITY · BINANCE_MANUAL_ENTRY_PRICE를 고치고 batch를 재시작하세요.",
                ),
            ),
        )
        // 2칸 행 다음 행이 1칸이라 채움 칸 없음
        val layout = e.toDiscordMessage().layout!!
        assertThat(layout.fields).isEqualTo(
            listOf(
                gf("빗썸 현물", "0.5 BTC · 평단 100,000,000원\n→ 0.6 BTC · 평단 101,000,000원", true),
                gf("바이낸스 선물 숏 (변경 없음)", "0.5 BTC · 평단 70,000 USDT", true),
                gf(
                    "확인할 것",
                    "바이낸스는 수동 입력값 그대로입니다. 바이낸스 포지션도 바꿨다면 " +
                        "BINANCE_MANUAL_SHORT_QUANTITY · BINANCE_MANUAL_ENTRY_PRICE를 고치고 batch를 재시작하세요.",
                    false,
                ),
            ),
        )
        assertThat(layout.author).isEqualTo("실계정 · BTC")
        assertThat(layout.timestamp).isEqualTo(bithumbChangedManual.detectedAt)
    }

    @Test
    fun `순수익률 급등 golden`() {
        val m = msg(swingUp)

        assertThat(m.eventKey).isEqualTo("account-pnl-swing-up-1792465200000")
        assertThat(m.mail.subject).isEqualTo("[실계정] 순수익률 급등 +0.50%p → +0.90%")
        assertThat(m.mail.body()).isEqualTo(
            """
            순수익률이 이번 시간 기준선보다 0.50%p 이상 올랐습니다.

            기준선 (12:00)  +0.40%
            현재            +0.90%
            변화            +0.50%p
            순손익          +123,457원

            같은 시간대에 같은 방향은 1번만 알립니다.

            기준 시각 2026-10-20 12:00 KST
            """.trimIndent(),
        )
        val e = m.discord
        assertThat(e.author).isEqualTo("실계정 · BTC")
        assertThat(e.title).isEqualTo("📈 순수익률 급등 +0.50%p → +0.90%")
        assertThat(e.color).isEqualTo(0x1ABC9C)
        assertThat(e.footer).isEqualTo("기준 ±0.50%p · 같은 시간대 같은 방향은 1번만 알림")
        assertThat(e.timestamp).isEqualTo(swingUp.pnl.calculatedAt)
        assertThat(e.rows).containsExactly(
            listOf(cell("기준선 (12:00)", "+0.40%"), cell("현재", "+0.90%"), cell("변화", "+0.50%p")),
            listOf(cell("순손익", "+123,457원")),
        )
        val layout = e.toDiscordMessage().layout!!
        assertThat(layout.fields).isEqualTo(
            listOf(
                gf("기준선 (12:00)", "+0.40%", true),
                gf("현재", "+0.90%", true),
                gf("변화", "+0.50%p", true),
                gf("순손익", "+123,457원", false),
            ),
        )
        assertThat(layout.author).isEqualTo("실계정 · BTC")
        assertThat(layout.timestamp).isEqualTo(swingUp.pnl.calculatedAt)
    }

    @Test
    fun `순수익률 급락 golden`() {
        val m = msg(swingDown)

        assertThat(m.eventKey).isEqualTo("account-pnl-swing-down-1792465200000")
        assertThat(m.mail.subject).isEqualTo("[실계정] 순수익률 급락 -0.50%p → -0.10%")
        assertThat(m.mail.body()).isEqualTo(
            """
            순수익률이 이번 시간 기준선보다 0.50%p 이상 내렸습니다.

            기준선 (12:00)  +0.40%
            현재            -0.10%
            변화            -0.50%p
            순손익          -12,345원

            같은 시간대에 같은 방향은 1번만 알립니다.

            기준 시각 2026-10-20 12:00 KST
            """.trimIndent(),
        )
        val e = m.discord
        assertThat(e.title).isEqualTo("📉 순수익률 급락 -0.50%p → -0.10%")
        assertThat(e.color).isEqualTo(0xE91E63)
        assertThat(e.footer).isEqualTo("기준 ±0.50%p · 같은 시간대 같은 방향은 1번만 알림")
        assertThat(e.rows).containsExactly(
            listOf(cell("기준선 (12:00)", "+0.40%"), cell("현재", "-0.10%"), cell("변화", "-0.50%p")),
            listOf(cell("순손익", "-12,345원")),
        )
        val layout = e.toDiscordMessage().layout!!
        assertThat(layout.fields).isEqualTo(
            listOf(
                gf("기준선 (12:00)", "+0.40%", true),
                gf("현재", "-0.10%", true),
                gf("변화", "-0.50%p", true),
                gf("순손익", "-12,345원", false),
            ),
        )
        assertThat(layout.author).isEqualTo("실계정 · BTC")
        assertThat(layout.timestamp).isEqualTo(swingDown.pnl.calculatedAt)
    }

    @Test
    fun `미반영 리마인더 4건 golden`() {
        val m = msg(reminderFour)

        assertThat(m.eventKey).isEqualTo("account-pnl-reminder-1792422000000")
        assertThat(m.mail.subject).isEqualTo("[실계정] 미반영 항목 4건")
        assertThat(m.mail.body()).isEqualTo(
            """
            순손익 계산에 빠졌거나 오래된 항목이 있습니다.

            ■ 순손익에 빠진 항목
            - 빗썸 진입 수수료 — 이력 없음
            - 바이낸스 진입 수수료 — 수동 미입력
            - 펀딩비 — 수동 미입력

            ■ 오래된 항목
            - 수동 펀딩비 3일째 그대로 (+3.2 USDT, 2026-10-17 09:30 KST 변경)

            하루 1번, 09:00 이후에 보냅니다.

            기준 시각 2026-10-20 09:30 KST
            """.trimIndent(),
        )
        val e = m.discord
        assertThat(e.author).isEqualTo("실계정 · BTC")
        assertThat(e.title).isEqualTo("🗒️ 미반영 항목 4건")
        assertThat(e.color).isEqualTo(0x7F8C8D)
        assertThat(e.footer).isEqualTo("하루 1번, 09:00 이후 발송")
        assertThat(e.timestamp).isEqualTo(reminderFour.detectedAt)
        assertThat(e.rows).containsExactly(
            listOf(
                cell(
                    "순손익에 빠진 항목",
                    "• 빗썸 진입 수수료 — 이력 없음\n• 바이낸스 진입 수수료 — 수동 미입력\n• 펀딩비 — 수동 미입력",
                ),
            ),
            listOf(cell("오래된 항목", "• 수동 펀딩비 3일째 그대로 (+3.2 USDT, 10-17 09:30 변경)")),
        )
        val layout = e.toDiscordMessage().layout!!
        assertThat(layout.fields).isEqualTo(
            listOf(
                gf(
                    "순손익에 빠진 항목",
                    "• 빗썸 진입 수수료 — 이력 없음\n• 바이낸스 진입 수수료 — 수동 미입력\n• 펀딩비 — 수동 미입력",
                    false,
                ),
                gf("오래된 항목", "• 수동 펀딩비 3일째 그대로 (+3.2 USDT, 10-17 09:30 변경)", false),
            ),
        )
        assertThat(layout.author).isEqualTo("실계정 · BTC")
        assertThat(layout.timestamp).isEqualTo(reminderFour.detectedAt)
    }

    // ---- 변형 ----

    @Test
    fun `빗썸과 바이낸스가 둘 다 바뀌면 제목은 빗썸·바이낸스이고 두 칸 모두 변경 전후 두 줄이다`() {
        val both = PositionChanged(
            before = PositionLegs(korea("0.5", "100000000"), foreign("-0.5", "70000")),
            after = PositionLegs(korea("0.6", "101000000"), foreign("-0.4", "71000.5")),
            hedgeMismatchWarning = false,
            source = ForeignPerpPositionSource.EXCHANGE_API,
            detectedAt = Instant.parse("2026-10-20T03:00:00Z"),
        )

        val m = msg(both)

        assertThat(m.mail.subject).isEqualTo("[실계정] 포지션 변경 — 빗썸·바이낸스")
        assertThat(m.discord.title).isEqualTo("🔄 포지션 변경 — 빗썸·바이낸스")
        assertThat(m.discord.rows).containsExactly(
            listOf(
                cell("빗썸 현물", "0.5 BTC · 평단 100,000,000원\n→ 0.6 BTC · 평단 101,000,000원"),
                cell("바이낸스 선물 숏", "0.5 BTC · 평단 70,000 USDT\n→ 0.4 BTC · 평단 71,000.5 USDT"),
            ),
        )
        assertThat(m.mail.body()).contains(
            "■ 빗썸 현물\n변경 전  0.5 BTC · 평단 100,000,000원\n변경 후  0.6 BTC · 평단 101,000,000원",
            "■ 바이낸스 선물 숏\n변경 전  0.5 BTC · 평단 70,000 USDT\n변경 후  0.4 BTC · 평단 71,000.5 USDT",
        )
    }

    @Test
    fun `바이낸스 leg 가 롱이면 값 앞에 롱 을 붙인다`() {
        val long = PositionChanged(
            before = PositionLegs(korea("0.6", "101000000"), ForeignLeg.FLAT),
            after = PositionLegs(korea("0.6", "101000000"), foreign("0.3", "70000")),
            hedgeMismatchWarning = false,
            source = ForeignPerpPositionSource.EXCHANGE_API,
            detectedAt = Instant.parse("2026-10-20T03:00:00Z"),
        )

        val m = msg(long)

        assertThat(m.discord.rows.single()[1])
            .isEqualTo(cell("바이낸스 선물 숏", "포지션 없음\n→ 롱 0.3 BTC · 평단 70,000 USDT"))
        assertThat(m.mail.body()).contains("변경 전  포지션 없음\n변경 후  롱 0.3 BTC · 평단 70,000 USDT")
    }

    @Test
    fun `안 바뀐 빗썸 leg 가 비어 있으면 포지션 없음 한 줄이다`() {
        val emptyKorea = PositionChanged(
            before = PositionLegs(KoreaLeg.EMPTY, foreign("-0.5", "70000")),
            after = PositionLegs(KoreaLeg.EMPTY, ForeignLeg.FLAT),
            hedgeMismatchWarning = false,
            source = ForeignPerpPositionSource.EXCHANGE_API,
            detectedAt = Instant.parse("2026-10-20T03:00:00Z"),
        )

        val m = msg(emptyKorea)

        assertThat(m.discord.rows.single()[0]).isEqualTo(cell("빗썸 현물 (변경 없음)", "포지션 없음"))
        assertThat(m.mail.body()).contains("■ 빗썸 현물 (변경 없음)\n포지션 없음")
    }

    @Test
    fun `리마인더에 오래된 항목만 있으면 빠진 항목 칸과 섹션이 없다`() {
        val staleOnly = reminderFour.copy(items = listOf(ReminderItem.MANUAL_FUNDING_STALE))

        val m = msg(staleOnly)

        assertThat(m.mail.subject).isEqualTo("[실계정] 미반영 항목 1건")
        assertThat(m.discord.rows.map { it.single().name }).containsExactly("오래된 항목")
        assertThat(m.mail.body()).doesNotContain("순손익에 빠진 항목").contains("■ 오래된 항목")
    }

    @Test
    fun `리마인더에 빠진 항목만 있으면 오래된 항목 칸과 섹션이 없다`() {
        val m = msg(reminderOne)

        assertThat(m.discord.title).isEqualTo("🗒️ 미반영 항목 1건")
        assertThat(m.discord.rows).containsExactly(listOf(cell("순손익에 빠진 항목", "• 빗썸 진입 수수료 — 이력 없음")))
        assertThat(m.mail.body()).doesNotContain("■ 오래된 항목")
            .contains("■ 순손익에 빠진 항목\n- 빗썸 진입 수수료 — 이력 없음")
        assertThat(m.mail.body()).contains("기준 시각 2026-10-20 15:00 KST")
    }

    @Test
    fun `바이낸스 API 모드 리마인더는 바이낸스 진입 수수료와 펀딩비를 이력 없음으로 쓴다`() {
        val api = reminderFour.copy(source = ForeignPerpPositionSource.EXCHANGE_API)

        assertThat(msg(api).discord.rows.first().single().value)
            .contains("• 바이낸스 진입 수수료 — 이력 없음", "• 펀딩비 — 이력 없음")
    }

    @Test
    fun `리마인더 시각은 reminderHour 에서 온다`() {
        val seven = reminderFour.copy(reminderHour = 7)

        val m = msg(seven)

        assertThat(m.discord.footer).isEqualTo("하루 1번, 07:00 이후 발송")
        assertThat(m.mail.body()).contains("하루 1번, 07:00 이후에 보냅니다.").doesNotContain("09:00 이후")
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
    fun `eventKey 는 4종 형식이고 Message-ID 규칙에 맞고 message 의 eventKey 와 같다`() {
        val alerts = listOf(bithumbChangedManual, swingUp, swingDown, reminderFour)
        val keys = alerts.map(AccountAlertContent::eventKey)

        assertThat(keys).containsExactly(
            "account-pnl-position-1792465200000",
            "account-pnl-swing-up-1792465200000",
            "account-pnl-swing-down-1792465200000",
            "account-pnl-reminder-1792422000000",
        )
        assertThat(keys).allSatisfy { assertThat(it).matches("[A-Za-z0-9._-]{1,128}") }
        assertThat(alerts.map { msg(it).eventKey }).isEqualTo(keys)
    }

    @Test
    fun `모든 결과에 주소 webhook env 원문이 없다`() {
        val alerts = listOf(bithumbChangedManual, binanceClosedApi, swingUp, swingDown, reminderFour, reminderOne)
        val texts = alerts.flatMap {
            val m = msg(it)
            listOf(m.mail.subject, m.mail.body(), m.discord.title, m.discord.footer.orEmpty()) +
                m.discord.rows.flatten().flatMap { c -> listOf(c.name, c.value) }
        }

        assertThat(texts).allSatisfy {
            assertThat(it).doesNotContain("example.test", "/api/webhooks/", "BINANCE_MANUAL_FUNDING_USDT=")
        }
    }
}
