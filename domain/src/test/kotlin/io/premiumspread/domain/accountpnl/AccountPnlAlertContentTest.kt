package io.premiumspread.domain.accountpnl

import io.premiumspread.domain.alertmessage.EmbedCell
import io.premiumspread.domain.alertmessage.GOLDEN_ROW_BREAK
import io.premiumspread.domain.alertmessage.gf
import io.premiumspread.domain.alertmessage.OwnerAlertMessage
import io.premiumspread.domain.owneralert.DiscordField
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId

/**
 * message-spec "실계정 순손익" 절의 구조 golden. 입력은 dod AC4(빗썸 0.266 BTC·평단 152,000,000·매수호가 158,500,000 ...).
 * 금액은 A4 에 따라 합계·순손익을 전체 정밀도에서 반올림하므로 spec 예시와 1원 차(`+469,353원`, `-73,847원`).
 */
class AccountPnlAlertContentTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private val fx = BigDecimal("1400")
    private val fxObservedAt = Instant.parse("2026-10-19T23:59:00Z")
    private val calculatedAt = Instant.parse("2026-10-20T00:00:00Z")
    private val observedAt = Instant.parse("2026-10-20T00:00:00Z")
    private val fakeSecrets = listOf("test-access-key", "test-secret", "@example.com")

    private fun level(price: String) = PriceLevel(BigDecimal(price), BigDecimal("10"))

    private fun pnl(
        entryFeeKrw: BigDecimal? = BigDecimal("16173"),
        entryCommission: BigDecimal? = BigDecimal("14.36"),
        funding: BigDecimal? = BigDecimal("4.2"),
        source: ForeignPerpPositionSource = ForeignPerpPositionSource.EXCHANGE_API,
        modified: Boolean = false,
        at: Instant = calculatedAt,
        krwBalance: BigDecimal? = BigDecimal("1234567"),
        usdtPrice: BigDecimal? = BigDecimal("1412"),
        balance: ForeignPerpBalance? = ForeignPerpBalance(BigDecimal("10000.50"), BigDecimal("8000.25")),
        symbolConfig: ForeignPerpSymbolConfig? = ForeignPerpSymbolConfig(5, ForeignPerpMarginType.ISOLATED),
        fxRate: BigDecimal = fx,
    ): AccountNetPnl {
        val korea = KoreaSpotAccountSnapshot(
            quantity = BigDecimal("0.266"),
            averageBuyPrice = BigDecimal("152000000"),
            averageBuyPriceModified = modified,
            entryFeeKrw = entryFeeKrw,
            sellFeeRate = BigDecimal("0.0004"),
            bidLevels = listOf(level("158500000")),
            lastPrice = BigDecimal("158500000"),
            bidObservedAt = observedAt,
            krwBalance = krwBalance,
            usdtLastPriceKrw = usdtPrice,
        )
        val foreign = ForeignPerpAccountSnapshot(
            signedQuantity = BigDecimal("-0.266"),
            entryPrice = BigDecimal("108000"),
            entryCommissionUsdt = entryCommission,
            fundingUsdt = funding,
            takerFeeRate = BigDecimal("0.0005"),
            askLevels = listOf(level("111200")),
            lastPrice = BigDecimal("111200"),
            askObservedAt = observedAt,
            positionSource = source,
            balance = balance,
            symbolConfig = symbolConfig,
        )
        return AccountNetPnl.calculate(AccountHedgePosition(korea, foreign), fxRate, fxObservedAt, at)
    }

    private fun cells(message: OwnerAlertMessage) = message.discord.rows

    private val profitBody = """
        ■ 김프
        현김프 1.78% · 매수김프 0.53% · 차이 +1.25%p
        환율 1,400.00원/USD

        ■ 빗썸 현물 (0.266 BTC)
        평단    152,000,000원
        현재가  158,500,000원
        손익    +1,729,000원
        평가액  42,161,000원

        ■ 바이낸스 선물 숏 (0.266 BTC)
        평단         108,000 USDT
        현재가       111,200 USDT
        손익         -1,191,680원 (-851.20 USDT)
        평가액       14,000,700원 (10,000.50 USDT)
        포지션 규모  29,579.20 USDT (5x)

        ■ 합산
        가격 차익  +537,320원
        수수료     -73,847원
          - 빗썸 진입           -16,173원
          - 바이낸스 진입       -20,104원 (14.36 USDT)
          - 빗썸 정리 예상      -16,864원
          - 바이낸스 정리 예상  -20,705원 (14.79 USDT)
        펀딩비     +5,880원 (+4.20 USDT)
        순손익     +469,353원 (+1.11%)

        ■ 자산
        총 자산      57,396,267원
        잔여 금액
          - 빗썸      1,234,567원
          - 바이낸스  11,200,350원 (8,000.25 USDT)
        USDT 원화가  1,412원

        기준 시각 2026-10-20 09:00 KST
    """.trimIndent()

    private val profitRows = listOf(
        listOf(EmbedCell("현김프", "1.78%"), EmbedCell("매수김프", "0.53%"), EmbedCell("차이", "+1.25%p")),
        listOf(
            EmbedCell(
                "빗썸 현물 · 0.266 BTC",
                "평단 152,000,000원\n현재가 158,500,000원\n손익 +1,729,000원\n평가액 42,161,000원",
            ),
            EmbedCell(
                "바이낸스 선물 숏 · 0.266 BTC",
                "평단 108,000 USDT\n현재가 111,200 USDT\n손익 -1,191,680원 (-851.20 USDT)\n" +
                    "평가액 14,000,700원 (10,000.50 USDT)\n포지션 규모 29,579.20 USDT (5x)",
            ),
        ),
        listOf(
            EmbedCell("가격 차익", "+537,320원"),
            EmbedCell("수수료 합계", "-73,847원"),
            EmbedCell("펀딩비", "+5,880원 (+4.20 USDT)"),
        ),
        listOf(
            EmbedCell("진입 수수료", "빗썸 -16,173원\n바이낸스 -20,104원 (14.36 USDT)"),
            EmbedCell("정리 수수료(예상)", "빗썸 -16,864원\n바이낸스 -20,705원 (14.79 USDT)"),
        ),
        listOf(EmbedCell("순손익", "**+469,353원 (+1.11%)**")),
        listOf(EmbedCell("총 자산", "57,396,267원")),
        listOf(
            EmbedCell("잔여 금액", "빗썸 1,234,567원\n바이낸스 11,200,350원 (8,000.25 USDT)"),
            EmbedCell("USDT 원화가", "1,412원"),
        ),
    )

    /** 수익·정시 공통 fields — 행 [3,2,3,2,1,1,2], 2번째 행 뒤 행 끊기 1개 = 15개 (design §3.3 규칙 1~2). 1칸 행은 non-inline 이고 2칸 행(잔여·USDT)은 뒤에 행이 없어 끊기 없음. */
    private val profitFields: List<DiscordField> = listOf(
        gf("현김프", "1.78%", true),
        gf("매수김프", "0.53%", true),
        gf("차이", "+1.25%p", true),
        gf(
            "빗썸 현물 · 0.266 BTC",
            "평단 152,000,000원\n현재가 158,500,000원\n손익 +1,729,000원\n평가액 42,161,000원",
            true,
        ),
        gf(
            "바이낸스 선물 숏 · 0.266 BTC",
            "평단 108,000 USDT\n현재가 111,200 USDT\n손익 -1,191,680원 (-851.20 USDT)\n" +
                "평가액 14,000,700원 (10,000.50 USDT)\n포지션 규모 29,579.20 USDT (5x)",
            true,
        ),
        GOLDEN_ROW_BREAK,
        gf("가격 차익", "+537,320원", true),
        gf("수수료 합계", "-73,847원", true),
        gf("펀딩비", "+5,880원 (+4.20 USDT)", true),
        gf("진입 수수료", "빗썸 -16,173원\n바이낸스 -20,104원 (14.36 USDT)", true),
        gf("정리 수수료(예상)", "빗썸 -16,864원\n바이낸스 -20,705원 (14.79 USDT)", true),
        gf("순손익", "**+469,353원 (+1.11%)**", false),
        gf("총 자산", "57,396,267원", false),
        gf("잔여 금액", "빗썸 1,234,567원\n바이낸스 11,200,350원 (8,000.25 USDT)", true),
        gf("USDT 원화가", "1,412원", true),
    )

    @Test
    fun `수익 알림 golden`() {
        val message = AccountPnlAlertContent.profit(pnl(), zone)

        assertThat(message.eventKey).isEqualTo("account-pnl-profit-1792454400000")
        assertThat(message.mail.subject).isEqualTo("[실계정] 수익 알림 +1.11% +469,353원")
        assertThat(message.mail.body()).isEqualTo(profitBody)
        val embed = message.discord
        assertThat(embed.author).isEqualTo("실계정 · BTC")
        assertThat(embed.title).isEqualTo("💰 수익 알림 +1.11% +469,353원")
        assertThat(embed.color).isEqualTo(0x2ECC71)
        assertThat(embed.footer).isEqualTo("환율 1,400.00원/USD")
        assertThat(embed.timestamp).isEqualTo(calculatedAt)
        assertThat(embed.rows).isEqualTo(profitRows)
        // 칸 14개 + 2번째 행 뒤 행 끊기 1개
        val layout = embed.toDiscordMessage().layout!!
        assertThat(layout.fields).isEqualTo(profitFields)
        assertThat(layout.author).isEqualTo("실계정 · BTC")
        assertThat(layout.timestamp).isEqualTo(calculatedAt)
    }

    @Test
    fun `정시 보고 golden`() {
        val message = AccountPnlAlertContent.hourly(pnl(at = Instant.parse("2026-10-20T00:00:30Z")), zone)

        assertThat(message.eventKey).isEqualTo("account-pnl-hourly-1792454400000")
        assertThat(message.mail.subject).isEqualTo("[실계정] 정시 보고 09:00 +1.11% +469,353원")
        assertThat(message.mail.body()).isEqualTo(profitBody)
        assertThat(message.discord.title).isEqualTo("📊 정시 보고 09:00 +1.11% +469,353원")
        assertThat(message.discord.author).isEqualTo("실계정 · BTC")
        assertThat(message.discord.color).isEqualTo(0x3498DB)
        assertThat(message.discord.footer).isEqualTo("매시 정각 발송 · 환율 1,400.00원/USD")
        assertThat(message.discord.rows).isEqualTo(profitRows)
        assertThat(message.discord.timestamp).isEqualTo(Instant.parse("2026-10-20T00:00:30Z"))
        val layout = message.discord.toDiscordMessage().layout!!
        assertThat(layout.fields).isEqualTo(profitFields)
        assertThat(layout.author).isEqualTo("실계정 · BTC")
        assertThat(layout.timestamp).isEqualTo(Instant.parse("2026-10-20T00:00:30Z"))
    }

    @Test
    fun `중단 빗썸 인증 실패 golden`() {
        val now = Instant.parse("2026-10-20T03:10:00Z")
        val message = AccountPnlAlertContent.outage(OutageNotice.Outage(AccountReadFailureCategory.BITHUMB_AUTH, null), now, zone)

        assertThat(message.eventKey).isEqualTo("account-pnl-outage-${now.toEpochMilli()}")
        assertThat(message.mail.subject).isEqualTo("[실계정] 모니터링 중단 — 빗썸 인증 실패")
        assertThat(message.mail.body()).isEqualTo(
            """
            실계정 순손익 계산이 5분 넘게 멈췄습니다.

            ■ 상태
            원인         빗썸 인증 실패
            마지막 성공  없음
            중단 감지    2026-10-20 12:10 KST

            ■ 확인할 것
            빗썸 Open API key의 허용 IP가 현재 공인 IP와 같은지 확인하세요.

            기준 시각 2026-10-20 12:10 KST
            """.trimIndent(),
        )
        val embed = message.discord
        assertThat(embed.author).isEqualTo("실계정 · BTC")
        assertThat(embed.title).isEqualTo("🚨 모니터링 중단 — 빗썸 인증 실패")
        assertThat(embed.color).isEqualTo(0xE74C3C)
        assertThat(embed.footer).isEqualTo("5분 넘게 계산하지 못하면 알림")
        assertThat(embed.timestamp).isEqualTo(now)
        assertThat(embed.rows).containsExactly(
            listOf(EmbedCell("마지막 성공", "없음"), EmbedCell("중단 감지", "12:10")),
            listOf(EmbedCell("확인할 것", "빗썸 Open API key의 허용 IP가 현재 공인 IP와 같은지 확인하세요.")),
        )
        // 2칸 inline 행 다음 행이 1칸이라 행 끊기 칸 없음
        val layout = embed.toDiscordMessage().layout!!
        assertThat(layout.fields).isEqualTo(
            listOf(
                gf("마지막 성공", "없음", true),
                gf("중단 감지", "12:10", true),
                gf("확인할 것", "빗썸 Open API key의 허용 IP가 현재 공인 IP와 같은지 확인하세요.", false),
            ),
        )
        assertThat(layout.author).isEqualTo("실계정 · BTC")
        assertThat(layout.timestamp).isEqualTo(now)
    }

    @Test
    fun `중단 환율 수집 지연 golden`() {
        val now = Instant.parse("2026-10-20T03:10:00Z")
        val lastSuccess = Instant.parse("2026-10-20T03:00:00Z")
        val message = AccountPnlAlertContent.outage(OutageNotice.Outage(AccountReadFailureCategory.STALE_FX, lastSuccess), now, zone)

        assertThat(message.mail.subject).isEqualTo("[실계정] 모니터링 중단 — 환율 수집 지연")
        assertThat(message.mail.body()).isEqualTo(
            """
            실계정 순손익 계산이 5분 넘게 멈췄습니다.

            ■ 상태
            원인         환율 수집 지연
            마지막 성공  2026-10-20 12:00 KST
            중단 감지    2026-10-20 12:10 KST

            기준 시각 2026-10-20 12:10 KST
            """.trimIndent(),
        )
        assertThat(message.discord.title).isEqualTo("🚨 모니터링 중단 — 환율 수집 지연")
        assertThat(message.discord.footer).isEqualTo("5분 넘게 계산하지 못하면 알림")
        assertThat(message.discord.rows).containsExactly(
            listOf(EmbedCell("마지막 성공", "12:00"), EmbedCell("중단 감지", "12:10")),
        )
        val layout = message.discord.toDiscordMessage().layout!!
        assertThat(layout.fields).isEqualTo(listOf(gf("마지막 성공", "12:00", true), gf("중단 감지", "12:10", true)))
        assertThat(layout.author).isEqualTo("실계정 · BTC")
        assertThat(layout.timestamp).isEqualTo(now)
    }

    @Test
    fun `복구 golden`() {
        val now = Instant.parse("2026-10-20T03:15:00Z")
        val message = AccountPnlAlertContent.recovered(now, zone)

        assertThat(message.eventKey).isEqualTo("account-pnl-recovered-${now.toEpochMilli()}")
        assertThat(message.mail.subject).isEqualTo("[실계정] 모니터링 복구")
        assertThat(message.mail.body()).isEqualTo(
            """
            실계정 순손익 계산이 다시 정상입니다.

            복구 시각  2026-10-20 12:15 KST
            """.trimIndent(),
        )
        assertThat(message.mail.basis).isNull()
        val embed = message.discord
        assertThat(embed.author).isEqualTo("실계정 · BTC")
        assertThat(embed.title).isEqualTo("✅ 모니터링 복구")
        assertThat(embed.color).isEqualTo(0x95A5A6)
        assertThat(embed.footer).isNull()
        assertThat(embed.timestamp).isEqualTo(now)
        assertThat(embed.rows).containsExactly(listOf(EmbedCell("복구 시각", "12:15")))
        val layout = embed.toDiscordMessage().layout!!
        assertThat(layout.fields).isEqualTo(listOf(gf("복구 시각", "12:15", false)))
        assertThat(layout.author).isEqualTo("실계정 · BTC")
        assertThat(layout.timestamp).isEqualTo(now)
    }

    // ---- 변형 ----

    @Test
    fun `진입 수수료와 펀딩이 없으면 이력 없음으로 표시하고 합계는 0 으로 계산한다`() {
        val message = AccountPnlAlertContent.profit(
            pnl(entryFeeKrw = null, entryCommission = null, funding = null, source = ForeignPerpPositionSource.EXCHANGE_API),
            zone,
        )

        val rows = message.discord.rows
        assertThat(rows[2][2]).isEqualTo(EmbedCell("펀딩비", "미반영 (이력 없음)"))
        assertThat(rows[3][0]).isEqualTo(EmbedCell("진입 수수료", "빗썸 미반영 (이력 없음)\n바이낸스 미반영 (이력 없음)"))
        assertThat(rows[2][1].value).isEqualTo("-37,570원")
        val body = message.mail.body()
        assertThat(body).contains("  - 빗썸 진입           미반영 (이력 없음)")
        assertThat(body).contains("  - 바이낸스 진입       미반영 (이력 없음)")
        assertThat(body).contains("펀딩비     미반영 (이력 없음)")
    }

    @Test
    fun `바이낸스 수동 입력 모드의 미반영은 수동 미입력이고 빗썸은 항상 이력 없음이다`() {
        val message = AccountPnlAlertContent.profit(
            pnl(entryFeeKrw = null, entryCommission = null, funding = null, source = ForeignPerpPositionSource.MANUAL_ENV),
            zone,
        )

        val rows = message.discord.rows
        assertThat(rows[2][2]).isEqualTo(EmbedCell("펀딩비", "미반영 (수동 미입력)"))
        assertThat(rows[3][0]).isEqualTo(EmbedCell("진입 수수료", "빗썸 미반영 (이력 없음)\n바이낸스 미반영 (수동 미입력)"))
        val body = message.mail.body()
        assertThat(body).contains("  - 바이낸스 진입       미반영 (수동 미입력)")
        assertThat(body).contains("펀딩비     미반영 (수동 미입력)")
    }

    // ---- 자산 묶음 변형 ----

    private fun assetRows(message: OwnerAlertMessage) = message.discord.rows.takeLast(2)

    private fun foreignCell(message: OwnerAlertMessage) = message.discord.rows[1][1]

    @Test
    fun `빗썸 KRW 잔고가 없으면 총 자산과 빗썸 잔여가 없음이다`() {
        val message = AccountPnlAlertContent.profit(pnl(krwBalance = null), zone)

        val body = message.mail.body()
        assertThat(body).contains("총 자산      없음")
        assertThat(body).contains("평가액       14,000,700원 (10,000.50 USDT)")
        assertThat(body).contains("  - 빗썸      없음")
        assertThat(assetRows(message)).isEqualTo(
            listOf(
                listOf(EmbedCell("총 자산", "없음")),
                listOf(
                    EmbedCell("잔여 금액", "빗썸 없음\n바이낸스 11,200,350원 (8,000.25 USDT)"),
                    EmbedCell("USDT 원화가", "1,412원"),
                ),
            ),
        )
    }

    @Test
    fun `바이낸스 잔고가 없으면 총 자산 증거금 바이낸스 잔여가 없음이다`() {
        val message = AccountPnlAlertContent.profit(pnl(balance = null), zone)

        val body = message.mail.body()
        assertThat(body).contains("총 자산      없음")
        assertThat(body).contains("평가액       없음")
        assertThat(body).doesNotContain("바이낸스 증거금+손익")
        assertThat(body).contains("  - 빗썸      1,234,567원")
        assertThat(body).contains("  - 바이낸스  없음")
        assertThat(assetRows(message)).isEqualTo(
            listOf(
                listOf(EmbedCell("총 자산", "없음")),
                listOf(EmbedCell("잔여 금액", "빗썸 1,234,567원\n바이낸스 없음"), EmbedCell("USDT 원화가", "1,412원")),
            ),
        )
    }

    @Test
    fun `USDT 원화가가 없으면 그 칸만 없음이고 총 자산은 유지된다`() {
        val message = AccountPnlAlertContent.profit(pnl(usdtPrice = null), zone)

        val body = message.mail.body()
        assertThat(body).contains("USDT 원화가  없음")
        assertThat(body).contains("총 자산      57,396,267원")
        assertThat(assetRows(message)[0][0]).isEqualTo(EmbedCell("총 자산", "57,396,267원"))
        assertThat(assetRows(message)[1][1]).isEqualTo(EmbedCell("USDT 원화가", "없음"))
    }

    @Test
    fun `수동 모드는 바이낸스 칸을 표시하지 않고 총 자산은 없음이다`() {
        val manual = "수동 모드 \u2014 표시 안 함"
        listOf(null, ForeignPerpBalance(BigDecimal("10000.50"), BigDecimal("8000.25"))).forEach { balance ->
            val message = AccountPnlAlertContent.profit(
                pnl(source = ForeignPerpPositionSource.MANUAL_ENV, balance = balance, symbolConfig = null),
                zone,
            )

            val body = message.mail.body()
            assertThat(body).contains("총 자산      없음")
            assertThat(body).contains("평가액       $manual")
            assertThat(body).doesNotContain("바이낸스 증거금+손익")
            assertThat(foreignCell(message).value).contains("평가액 $manual\n포지션 규모")
            assertThat(foreignCell(message).value).endsWith("\n포지션 규모 29,579.20 USDT")
            assertThat(body).contains("포지션 규모  29,579.20 USDT\n")
            assertThat(body).contains("  - 빗썸      1,234,567원")
            assertThat(body).contains("  - 바이낸스  $manual")
            assertThat(body).contains("USDT 원화가  1,412원")
            assertThat(assetRows(message)).isEqualTo(
                listOf(
                    listOf(EmbedCell("총 자산", "없음")),
                    listOf(EmbedCell("잔여 금액", "빗썸 1,234,567원\n바이낸스 $manual"), EmbedCell("USDT 원화가", "1,412원")),
                ),
            )
        }
    }

    @Test
    fun `세 평가액의 합은 총 자산과 같다`() {
        val message = AccountPnlAlertContent.profit(
            pnl(
                krwBalance = BigDecimal("1234567.6"),
                balance = ForeignPerpBalance(BigDecimal("10000.57"), BigDecimal("8000.25")),
                fxRate = BigDecimal("1387.37"),
            ),
            zone,
        )

        val body = message.mail.body()
        fun krwAfter(label: String, from: Int = 0): Long {
            val line = body.substring(from).lineSequence().first { it.contains(label) }
            return Regex("([0-9,]+)원").find(line.substringAfter(label))!!.groupValues[1].replace(",", "").toLong()
        }
        val bithumbValue = krwAfter("평가액", body.indexOf("■ 빗썸 현물"))
        val binanceValue = krwAfter("평가액", body.indexOf("■ 바이낸스 선물 숏"))
        val bithumbRemain = krwAfter("- 빗썸", body.indexOf("■ 자산"))
        val total = krwAfter("총 자산")

        assertThat(Math.abs(bithumbValue + bithumbRemain + binanceValue - total)).isLessThanOrEqualTo(2L)
    }

    @Test
    fun `레버리지를 모르면 포지션 규모에 배수를 붙이지 않는다`() {
        val message = AccountPnlAlertContent.profit(pnl(symbolConfig = null), zone)

        assertThat(message.mail.body()).contains("포지션 규모  29,579.20 USDT\n")
        assertThat(message.mail.body()).doesNotContain("x)")
        assertThat(foreignCell(message).value).endsWith("\n포지션 규모 29,579.20 USDT")
    }

    @Test
    fun `빗썸 KRW 잔고는 원 단위로 HALF_UP 반올림한다`() {
        val message = AccountPnlAlertContent.profit(pnl(krwBalance = BigDecimal("1234567.5")), zone)

        assertThat(assetRows(message)[1][0].value).startsWith("빗썸 1,234,568원\n")
    }

    @Test
    fun `평단 수정 표시는 쓰지 않는다`() {
        listOf(true, false).forEach { modified ->
            val message = AccountPnlAlertContent.profit(pnl(modified = modified), zone)
            assertThat(message.mail.body()).doesNotContain("수정")
            assertThat(message.discord.rows.flatten().joinToString { it.name + it.value }).doesNotContain("수정")
            assertThat(message.discord.footer).doesNotContain("수정")
        }
        assertThat(AccountPnlAlertContent.profit(pnl(modified = true), zone).mail.body())
            .isEqualTo(AccountPnlAlertContent.profit(pnl(modified = false), zone).mail.body())
    }

    @Test
    fun `실패 범주 label 7개 전부`() {
        val expected = mapOf(
            AccountReadFailureCategory.BITHUMB_AUTH to "빗썸 인증 실패",
            AccountReadFailureCategory.BINANCE_AUTH to "바이낸스 인증 실패",
            AccountReadFailureCategory.NETWORK to "네트워크·요청 한도 오류",
            AccountReadFailureCategory.STALE_MARKET_DATA to "시세 지연",
            AccountReadFailureCategory.STALE_FX to "환율 수집 지연",
            AccountReadFailureCategory.INSUFFICIENT_DEPTH to "호가 깊이 부족",
            AccountReadFailureCategory.UNEXPECTED to "기타 오류(로그 확인)",
        )
        assertThat(expected.keys).containsExactlyInAnyOrder(*AccountReadFailureCategory.entries.toTypedArray())
        val now = Instant.parse("2026-10-20T03:10:00Z")
        expected.forEach { (category, label) ->
            val message = AccountPnlAlertContent.outage(OutageNotice.Outage(category, now), now, zone)
            assertThat(AccountPnlAlertContent.label(category)).isEqualTo(label)
            assertThat(message.discord.title).isEqualTo("🚨 모니터링 중단 — $label")
            assertThat(message.mail.subject).isEqualTo("[실계정] 모니터링 중단 — $label")
            val needsCheck = category == AccountReadFailureCategory.BITHUMB_AUTH
            assertThat(message.mail.body().contains("■ 확인할 것")).isEqualTo(needsCheck)
            assertThat(message.discord.rows.size).isEqualTo(if (needsCheck) 2 else 1)
        }
    }

    // ---- 삭제된 옛 메일·Discord 문구 테스트에서 옮긴 비문구 계약 ----

    @Test
    fun `4종 eventKey 는 SMTP deliveryId 형식을 만족하고 서로 다르다`() {
        // 출처: supports/email JavaMailEmailSender.DELIVERY_ID_PATTERN — 어긋나면 발송이 매번 예외로 끝난다
        val deliveryIdPattern = Regex("[A-Za-z0-9._-]{1,128}")
        val now = Instant.parse("2026-10-20T01:00:00Z")
        val keys = listOf(
            AccountPnlAlertContent.profit(pnl(), zone).eventKey,
            AccountPnlAlertContent.hourly(pnl(), zone).eventKey,
            AccountPnlAlertContent.outage(OutageNotice.Outage(AccountReadFailureCategory.NETWORK, null), now, zone).eventKey,
            AccountPnlAlertContent.recovered(now, zone).eventKey,
        )

        keys.forEach { key -> assertThat(deliveryIdPattern.matches(key)).describedAs(key).isTrue() }
        assertThat(keys).doesNotHaveDuplicates()
    }

    @Test
    fun `메일 제목 본문과 Discord 모든 문자열에 비밀 문자열이 없다`() {
        val now = Instant.parse("2026-10-20T01:00:00Z")
        val messages = listOf(
            AccountPnlAlertContent.profit(pnl(), zone),
            AccountPnlAlertContent.hourly(pnl(), zone),
            AccountPnlAlertContent.outage(OutageNotice.Outage(AccountReadFailureCategory.BINANCE_AUTH, null), now, zone),
            AccountPnlAlertContent.outage(OutageNotice.Outage(AccountReadFailureCategory.BITHUMB_AUTH, now), now, zone),
            AccountPnlAlertContent.recovered(now, zone),
        )
        messages.forEach { message ->
            val texts = listOf(message.mail.subject, message.mail.body(), message.discord.title, message.discord.author) +
                listOfNotNull(message.discord.footer) +
                message.discord.rows.flatten().flatMap { listOf(it.name, it.value) }
            texts.forEach { text -> fakeSecrets.forEach { secret -> assertThat(text).doesNotContain(secret) } }
        }
    }

    @Test
    fun `정시 eventKey 는 같은 시간대면 같고 다음 시간대는 다음 정시다`() {
        fun key(at: String) = AccountPnlAlertContent.hourly(pnl(at = Instant.parse(at)), zone).eventKey

        assertThat(key("2026-10-20T00:00:00Z")).isEqualTo("account-pnl-hourly-1792454400000")
        assertThat(key("2026-10-20T00:59:59Z")).isEqualTo("account-pnl-hourly-1792454400000")
        assertThat(key("2026-10-20T01:00:00Z")).isEqualTo("account-pnl-hourly-1792458000000")
    }

    @Test
    fun `색 상수 값`() {
        assertThat(AccountPnlAlertContent.COLOR_PROFIT).isEqualTo(3066993)
        assertThat(AccountPnlAlertContent.COLOR_HOURLY).isEqualTo(3447003)
        assertThat(AccountPnlAlertContent.COLOR_OUTAGE).isEqualTo(15158332)
        assertThat(AccountPnlAlertContent.COLOR_RECOVERED).isEqualTo(9807270)
    }
}
