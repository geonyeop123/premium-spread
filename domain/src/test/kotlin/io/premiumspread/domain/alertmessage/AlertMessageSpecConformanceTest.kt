package io.premiumspread.domain.alertmessage

import io.premiumspread.domain.accountpnl.AccountAlert
import io.premiumspread.domain.accountpnl.AccountAlertContent
import io.premiumspread.domain.accountpnl.AccountHedgePosition
import io.premiumspread.domain.accountpnl.AccountNetPnl
import io.premiumspread.domain.accountpnl.AccountPnlAlertContent
import io.premiumspread.domain.accountpnl.AccountReadFailureCategory
import io.premiumspread.domain.accountpnl.ForeignLeg
import io.premiumspread.domain.accountpnl.ForeignPerpAccountSnapshot
import io.premiumspread.domain.accountpnl.ForeignPerpPositionSource
import io.premiumspread.domain.accountpnl.KoreaLeg
import io.premiumspread.domain.accountpnl.KoreaSpotAccountSnapshot
import io.premiumspread.domain.accountpnl.ManualFundingStaleness
import io.premiumspread.domain.accountpnl.OutageNotice
import io.premiumspread.domain.accountpnl.PositionLegs
import io.premiumspread.domain.accountpnl.PriceLevel
import io.premiumspread.domain.accountpnl.ReminderItem
import io.premiumspread.domain.accountpnl.SwingDirection
import io.premiumspread.domain.exchangerate.ExchangeRateSnapshot
import io.premiumspread.domain.job.JobId
import io.premiumspread.domain.market.MarketPair
import io.premiumspread.domain.notification.ActiveNotificationSubscription
import io.premiumspread.domain.notification.PremiumThresholdMailContent
import io.premiumspread.domain.notification.ThresholdDirection
import io.premiumspread.domain.ownermarket.ExtremeWindow
import io.premiumspread.domain.ownermarket.MarketAlertSettings
import io.premiumspread.domain.ownermarket.MarketDirection
import io.premiumspread.domain.ownermarket.MarketNotice
import io.premiumspread.domain.ownermarket.OwnerMarketAlertContent
import io.premiumspread.domain.ownermarket.SummaryPnl
import io.premiumspread.domain.ownermarket.ThresholdLine
import io.premiumspread.domain.ownerops.OpsAlertSettings
import io.premiumspread.domain.ownerops.OpsNotice
import io.premiumspread.domain.ownerops.OpsObservation
import io.premiumspread.domain.ownerops.OpsSignal
import io.premiumspread.domain.ownerops.OwnerOpsAlertContent
import io.premiumspread.domain.premium.PremiumAggregationSnapshot
import io.premiumspread.domain.premium.PremiumSnapshot
import io.premiumspread.domain.ticker.Currency
import io.premiumspread.domain.ticker.Exchange
import io.premiumspread.domain.ticker.Quote
import io.premiumspread.domain.ticker.Symbol
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * dod AC10 시나리오 표 22행의 전수 적합성. 행마다 (a) 메일 제목 접두어 (b) `기준 시각` 줄 유무·위치
 * (c) Discord author·footer 유무·timestamp·행별 칸 수 (d) 금지어 없음 (e) Discord 한도를 검사한다.
 * 입력 fixture 는 이 파일 안에서 만든다(다른 테스트 파일에 의존하지 않음).
 */
class AlertMessageSpecConformanceTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private val forbidden = Regex(
        "청산|gross|\\bBITHUMB\\b|\\bBINANCE\\b|docs/runbooks|조치 안내|VWAP|체결가|대조용|UTC|거래소 API|" +
            "수동 입력\\(env\\)|Exception|http|example\\.test|사용자 수정",
    )

    private class Scenario(
        val no: Int,
        val name: String,
        val prefix: String,
        val basisLine: Boolean,
        val author: String,
        val footer: Boolean,
        val cellsPerRow: List<Int>,
        val timestamp: Instant,
        val message: OwnerAlertMessage,
    )

    private fun bd(v: String) = BigDecimal(v)

    // ---- 실계정 ----
    private val at = Instant.parse("2026-10-20T00:00:00Z")
    private val outageNow = Instant.parse("2026-10-20T03:10:00Z")

    private fun level(price: String) = PriceLevel(bd(price), bd("10"))

    private fun pnl(calculatedAt: Instant = at): AccountNetPnl {
        val korea = KoreaSpotAccountSnapshot(
            quantity = bd("0.266"),
            averageBuyPrice = bd("152000000"),
            averageBuyPriceModified = false,
            entryFeeKrw = bd("16173"),
            sellFeeRate = bd("0.0004"),
            bidLevels = listOf(level("158500000")),
            lastPrice = bd("158500000"),
            bidObservedAt = at,
        )
        val foreign = ForeignPerpAccountSnapshot(
            signedQuantity = bd("-0.266"),
            entryPrice = bd("108000"),
            entryCommissionUsdt = bd("14.36"),
            fundingUsdt = bd("4.2"),
            takerFeeRate = bd("0.0005"),
            askLevels = listOf(level("111200")),
            lastPrice = bd("111200"),
            askObservedAt = at,
            positionSource = ForeignPerpPositionSource.EXCHANGE_API,
        )
        return AccountNetPnl.calculate(
            AccountHedgePosition(korea, foreign),
            bd("1400"),
            Instant.parse("2026-10-19T23:59:00Z"),
            calculatedAt,
        )
    }

    private fun korea(quantity: String, average: String) = KoreaLeg.of(bd(quantity), bd(average))
    private fun foreign(quantity: String, entry: String) = ForeignLeg.of(bd(quantity), bd(entry))

    private val changeDetectedAt = Instant.parse("2026-10-20T03:00:00Z")
    private val positionChanged = AccountAlert.PositionChanged(
        before = PositionLegs(korea("0.6", "101000000"), foreign("-0.5", "70000")),
        after = PositionLegs(korea("0.6", "101000000"), ForeignLeg.FLAT),
        hedgeMismatchWarning = false,
        source = ForeignPerpPositionSource.EXCHANGE_API,
        detectedAt = changeDetectedAt,
    )
    private val positionChangedMismatch = AccountAlert.PositionChanged(
        before = PositionLegs(korea("0.5", "100000000"), foreign("-0.5", "70000")),
        after = PositionLegs(korea("0.6", "101000000"), foreign("-0.5", "70000")),
        hedgeMismatchWarning = true,
        source = ForeignPerpPositionSource.MANUAL_ENV,
        detectedAt = changeDetectedAt,
    )

    private fun swing(direction: SwingDirection) = AccountAlert.NetPercentSwing(
        direction = direction,
        bucketStart = changeDetectedAt,
        baselinePercent = bd("0.40"),
        threshold = bd("0.50"),
        pnl = pnl(),
    )

    private val reminder = AccountAlert.UnrecoveredReminder(
        dayStart = Instant.parse("2026-10-19T15:00:00Z"),
        items = listOf(
            ReminderItem.KOREA_ENTRY_FEE,
            ReminderItem.FOREIGN_ENTRY_FEE,
            ReminderItem.FUNDING,
            ReminderItem.MANUAL_FUNDING_STALE,
        ),
        source = ForeignPerpPositionSource.MANUAL_ENV,
        manualFunding = ManualFundingStaleness(bd("3.2"), Instant.parse("2026-10-17T00:30:00Z"), 3),
        reminderHour = 9,
        detectedAt = Instant.parse("2026-10-20T00:30:00Z"),
    )

    // ---- 운영 ----
    private val opsAt = Instant.parse("2026-10-28T00:00:00Z")
    private val opsSettings = OpsAlertSettings()
    private val fxSignal = OpsSignal.FxIngestion(Currency.USD, Currency.KRW)
    private val bithumb = OpsSignal.MarketStream(Exchange.BITHUMB, Quote.coin(Symbol("BTC"), Currency.KRW))
    private val binance = OpsSignal.MarketStream(Exchange.BINANCE, Quote.coin(Symbol("BTC"), Currency.USD))
    private val job = OpsSignal.JobFailures(JobId.PREMIUM_REALTIME)
    private val jobAt = Instant.parse("2026-10-27T18:00:00Z")

    // ---- 시장 ----
    private val pair = MarketPair.default(Symbol("BTC"))
    private val marketAt = Instant.parse("2026-10-29T00:00:00Z")
    private val observed = Instant.parse("2026-10-28T23:59:00Z")
    private val marketSettings = MarketAlertSettings()
    private val marketAuthor = "시장 · BTC 빗썸/바이낸스"
    private val summaryDay = PremiumAggregationSnapshot(
        pair, bd("2.80"), bd("1.90"), bd("2.10"), bd("2.50"), bd("2.35"), 1440,
        Instant.parse("2026-10-27T15:00:00Z"), bd("1385.20"),
    )
    private val summaryFx = ExchangeRateSnapshot("USD", "KRW", bd("1390.50"), observed)
    private val summaryPnl = SummaryPnl.Value(bd("1.23"), bd("123456.7"), Instant.parse("2026-10-28T23:58:00Z"))

    private fun market(notice: MarketNotice) = OwnerMarketAlertContent.message(notice, marketSettings, zone)
    private fun ops(notice: OpsNotice) = OwnerOpsAlertContent.message(notice, opsSettings, zone)

    private fun scenarios(): List<Scenario> {
        val hourlyAt = Instant.parse("2026-10-20T00:00:30Z")
        val recoveredAt = Instant.parse("2026-10-20T03:15:00Z")
        val outageBithumb = AccountPnlAlertContent.outage(
            OutageNotice.Outage(AccountReadFailureCategory.BITHUMB_AUTH, null),
            outageNow,
            zone,
        )
        val outageFx = AccountPnlAlertContent.outage(
            OutageNotice.Outage(AccountReadFailureCategory.STALE_FX, Instant.parse("2026-10-20T03:00:00Z")),
            outageNow,
            zone,
        )
        val real = "[실계정]"
        val realAuthor = "실계정 · BTC"
        val opsPrefix = "[운영]"
        val marketPrefix = "[시장]"
        return listOf(
            Scenario(1, "수익", real, true, realAuthor, true, listOf(3, 2, 3, 2, 1), at, AccountPnlAlertContent.profit(pnl(), zone)),
            Scenario(
                2, "정시 보고", real, true, realAuthor, true, listOf(3, 2, 3, 2, 1), hourlyAt,
                AccountPnlAlertContent.hourly(pnl(hourlyAt), zone),
            ),
            Scenario(3, "중단(빗썸 인증)", real, true, realAuthor, true, listOf(2, 1), outageNow, outageBithumb),
            Scenario(4, "중단(환율 지연)", real, true, realAuthor, true, listOf(2), outageNow, outageFx),
            Scenario(
                5, "복구", real, false, realAuthor, false, listOf(1), recoveredAt,
                AccountPnlAlertContent.recovered(recoveredAt, zone),
            ),
            Scenario(
                6, "포지션 변경", real, true, realAuthor, true, listOf(2), changeDetectedAt,
                AccountAlertContent.message(positionChanged, zone),
            ),
            Scenario(
                7, "포지션 변경(헤지 불일치)", real, true, realAuthor, true, listOf(2, 1), changeDetectedAt,
                AccountAlertContent.message(positionChangedMismatch, zone),
            ),
            Scenario(
                8, "순수익률 급등", real, true, realAuthor, true, listOf(3, 1), at,
                AccountAlertContent.message(swing(SwingDirection.UP), zone),
            ),
            Scenario(
                9, "순수익률 급락", real, true, realAuthor, true, listOf(3, 1), at,
                AccountAlertContent.message(swing(SwingDirection.DOWN), zone),
            ),
            Scenario(
                10, "미반영 리마인더", real, true, realAuthor, true, listOf(1, 1), reminder.detectedAt,
                AccountAlertContent.message(reminder, zone),
            ),
            Scenario(
                11, "환율 수집 지연", opsPrefix, true, "운영", true, listOf(3), opsAt,
                ops(OpsNotice.Alert(fxSignal, opsAt, opsAt, 1, OpsObservation.Down(Instant.parse("2026-10-27T23:29:00Z")))),
            ),
            Scenario(
                12, "시세 끊김", opsPrefix, true, "운영", true, listOf(3), opsAt,
                ops(OpsNotice.Alert(bithumb, opsAt, opsAt, 1, OpsObservation.Down(Instant.parse("2026-10-27T23:54:00Z")))),
            ),
            Scenario(
                13, "Job 연속 실패(재알림)", opsPrefix, true, "운영", true, listOf(3), opsAt,
                ops(OpsNotice.Alert(job, jobAt, opsAt, 2, OpsObservation.Down(Instant.parse("2026-10-27T17:55:00Z"), 4))),
            ),
            Scenario(
                14, "운영 장애 해소", opsPrefix, true, "운영", false, listOf(3, 1), opsAt,
                ops(OpsNotice.Recovered(binance, Instant.parse("2026-10-27T12:00:00Z"), opsAt, 2)),
            ),
            Scenario(
                15, "김프 급등", marketPrefix, true, marketAuthor, true, listOf(3), marketAt,
                market(
                    MarketNotice.Swing(
                        pair,
                        marketAt,
                        MarketDirection.UP,
                        Duration.ofMinutes(10),
                        bd("2.00"),
                        bd("2.60"),
                        observed,
                        bd("0.60"),
                    ),
                ),
            ),
            Scenario(
                16, "김프 급락", marketPrefix, true, marketAuthor, true, listOf(3), marketAt,
                market(
                    MarketNotice.Swing(
                        pair,
                        marketAt,
                        MarketDirection.DOWN,
                        Duration.ofMinutes(10),
                        bd("2.60"),
                        bd("2.00"),
                        observed,
                        bd("-0.60"),
                    ),
                ),
            ),
            Scenario(
                17, "상단 기준선 돌파", marketPrefix, true, marketAuthor, true, listOf(2), marketAt,
                market(MarketNotice.ThresholdCrossed(pair, marketAt, ThresholdLine.UPPER, bd("3.0"), bd("0.1"), bd("3.05"), observed)),
            ),
            Scenario(
                18, "하단 기준선 이탈", marketPrefix, true, marketAuthor, true, listOf(2), marketAt,
                market(MarketNotice.ThresholdCrossed(pair, marketAt, ThresholdLine.LOWER, bd("-1.0"), bd("0.1"), bd("-1.04"), observed)),
            ),
            Scenario(
                19, "24시간 신고가", marketPrefix, true, marketAuthor, true, listOf(3), marketAt,
                market(MarketNotice.NewExtreme(pair, marketAt, ExtremeWindow.ONE_DAY, MarketDirection.UP, bd("2.50"), bd("2.70"), observed)),
            ),
            Scenario(
                20, "1시간 신저가", marketPrefix, true, marketAuthor, true, listOf(3), marketAt,
                market(MarketNotice.NewExtreme(pair, marketAt, ExtremeWindow.ONE_HOUR, MarketDirection.DOWN, bd("1.50"), bd("1.35"), observed)),
            ),
            Scenario(
                21, "일일 요약", marketPrefix, true, marketAuthor, true, listOf(3, 2, 2, 1), marketAt,
                market(
                    MarketNotice.DailySummary(
                        pair,
                        marketAt,
                        LocalDate.parse("2026-10-28"),
                        summaryDay,
                        Currency.USD,
                        Currency.KRW,
                        summaryFx,
                        summaryPnl,
                    ),
                ),
            ),
        )
    }

    private fun memberMail(): MailDocument {
        val subscription = ActiveNotificationSubscription(
            id = 10,
            memberId = 1,
            memberEmail = "member@example.com",
            memberNickname = "member",
            pair = pair,
            revision = 3,
            direction = ThresholdDirection.ABOVE,
            threshold = bd("5.00"),
        )
        val snapshot = PremiumSnapshot(
            pair = pair,
            premiumRate = bd("6"),
            koreaPrice = bd("100"),
            foreignPrice = bd("90"),
            foreignPriceInKrw = bd("94"),
            fxRate = bd("1400"),
            observedAt = Instant.parse("2026-07-15T00:30:00Z"),
        )
        return PremiumThresholdMailContent.render(subscription, snapshot, zone)
    }

    private fun checkMail(mail: MailDocument, prefix: String, basisLine: Boolean) {
        assertThat(mail.subject).`as`("메일 제목 접두어").startsWith("$prefix ")
        val lines = mail.body().lines()
        val basis = lines.filter { it.startsWith("기준 시각 ") }
        if (basisLine) {
            assertThat(basis).hasSize(1)
            assertThat(basis.single()).matches("기준 시각 \\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2} KST")
            // owner 메일에는 서명이 없으므로 기준 시각 줄이 마지막이다.
            assertThat(lines.last()).isEqualTo(basis.single())
        } else {
            assertThat(basis).isEmpty()
        }
        assertThat(forbidden.find(mail.subject + "\n" + mail.body())).`as`("메일 금지어").isNull()
    }

    @TestFactory
    fun `22종 알림은 spec 구조와 같다`(): List<DynamicTest> = scenarios().map { s ->
        dynamicTest("${s.no}. ${s.name}") {
            checkMail(s.message.mail, s.prefix, s.basisLine)

            val embed = s.message.discord
            assertThat(embed.author).isEqualTo(s.author)
            assertThat(embed.footer != null).`as`("footer 유무").isEqualTo(s.footer)
            assertThat(embed.timestamp).isEqualTo(s.timestamp)
            assertThat(embed.rows.map { it.size }).`as`("행별 칸 수").isEqualTo(s.cellsPerRow)

            val discord = embed.toDiscordMessage()
            val layout = discord.layout!!
            val texts = listOfNotNull(discord.title, discord.description, layout.author, layout.footer) +
                layout.fields.flatMap { listOf(it.name, it.value) }
            assertThat(texts.filter { forbidden.containsMatchIn(it) }).`as`("Discord 금지어").isEmpty()

            // (e) Discord 한도
            assertThat(layout.fields.size).isLessThanOrEqualTo(25)
            assertThat(layout.fields).allSatisfy {
                assertThat(it.name.length).isLessThanOrEqualTo(256)
                assertThat(it.value.length).isLessThanOrEqualTo(1024)
            }
            val total = discord.title.length + layout.author!!.length + (layout.footer?.length ?: 0) +
                layout.fields.sumOf { it.name.length + it.value.length }
            assertThat(total).isLessThanOrEqualTo(6000)
            assertThat(discord.title.length).isLessThanOrEqualTo(255)
        }
    } + dynamicTest("22. 회원 임계값 도달") {
        val mail = memberMail()
        assertThat(mail.subject).startsWith("[김프 알림] ")
        assertThat(mail.basis).isNull()
        assertThat(mail.body().lines().none { it.startsWith("기준 시각 ") }).isTrue()
        assertThat(mail.body().lines().takeLast(2)).containsExactly("--", "premium-spread")
        assertThat(forbidden.find(mail.subject + "\n" + mail.body())).isNull()
    }
}
