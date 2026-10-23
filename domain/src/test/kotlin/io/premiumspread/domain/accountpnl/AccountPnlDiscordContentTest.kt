package io.premiumspread.domain.accountpnl

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId

class AccountPnlDiscordContentTest {

    private val zone = ZoneId.of("Asia/Seoul")
    private val calculatedAt = Instant.parse("2026-10-20T00:00:05Z")
    private val now = Instant.parse("2026-10-20T00:30:00Z")

    private fun pnl(
        modified: Boolean = false,
        koreaFeeUnrecovered: Boolean = false,
        foreignFeeUnrecovered: Boolean = false,
        fundingUnrecovered: Boolean = false,
        at: Instant = calculatedAt,
    ) = AccountNetPnl(
        grossKrw = BigDecimal("500000.5"),
        entryFeeKrw = BigDecimal("23823.65"),
        fundingKrw = BigDecimal("4251.2"),
        exitFeeEstimateKrw = BigDecimal("15000.1"),
        netKrw = BigDecimal("456789.4"),
        netPercent = BigDecimal("1.23"),
        grossKrwAtLast = BigDecimal("490000"),
        exitFeeEstimateKrwAtLast = BigDecimal("15000"),
        netKrwAtLast = BigDecimal("455000"),
        netPercentAtLast = BigDecimal("1.20"),
        koreaExitPrice = BigDecimal("109500000"),
        foreignExitPrice = BigDecimal("81580"),
        koreaEntryFeeUnrecovered = koreaFeeUnrecovered,
        foreignEntryFeeUnrecovered = foreignFeeUnrecovered,
        fundingUnrecovered = fundingUnrecovered,
        position = AccountHedgePosition(
            KoreaSpotAccountSnapshot(
                quantity = BigDecimal("0.266"),
                averageBuyPrice = BigDecimal("113016278"),
                averageBuyPriceModified = modified,
                entryFeeKrw = if (koreaFeeUnrecovered) null else BigDecimal("12000"),
                sellFeeRate = BigDecimal("0.0004"),
                bidLevels = listOf(PriceLevel(BigDecimal("109500000"), BigDecimal("1.0"))),
                lastPrice = BigDecimal("109575000"),
                bidObservedAt = Instant.parse("2026-10-20T00:00:00Z"),
            ),
            ForeignPerpAccountSnapshot(
                signedQuantity = BigDecimal("-0.266"),
                entryPrice = BigDecimal("83549.4"),
                entryCommissionUsdt = if (foreignFeeUnrecovered) null else BigDecimal("8.9"),
                fundingUsdt = if (fundingUnrecovered) null else BigDecimal("3.2"),
                takerFeeRate = BigDecimal("0.0005"),
                askLevels = listOf(PriceLevel(BigDecimal("81580"), BigDecimal("5.0"))),
                lastPrice = BigDecimal("81577"),
                askObservedAt = Instant.parse("2026-10-20T00:00:01Z"),
            ),
        ),
        fxRate = BigDecimal("1328.5"),
        fxObservedAt = Instant.parse("2026-10-19T23:30:00Z"),
        calculatedAt = at,
    )

    private fun manual(pnl: AccountNetPnl) = pnl.copy(
        position = pnl.position.copy(
            foreign = pnl.position.foreign.copy(positionSource = ForeignPerpPositionSource.MANUAL_ENV),
        ),
    )

    private val baseLines = listOf(
        "순수익률 +1.23% · 순손익 +456,789원",
        "체결가 기준 +1.20% (+455,000원)",
        "빗썸 청산가 109,500,000원 × 0.266 BTC (호가 VWAP)",
        "바이낸스 청산가 81,580 USDT × 0.266 BTC · 환율 1,328.50",
        "gross +500,001원 · 예상 청산 수수료 -15,000원",
        "진입 수수료 -23,824원 · 펀딩 +4,251원",
    )

    @Test
    fun `수익 Discord 는 기준 golden 과 같다`() {
        val message = AccountPnlDiscordContent.profit(pnl(), zone)

        assertThat(message.title).isEqualTo("💰 수익 알림 · 순수익률 +1.23%")
        assertThat(message.description).isEqualTo(
            (baseLines + "바이낸스 포지션: 거래소 API · 2026-10-20 09:00 KST").joinToString("\n"),
        )
        assertThat(message.color).isEqualTo(AccountPnlDiscordContent.COLOR_PROFIT)
    }

    @Test
    fun `정시 보고 제목은 zone 의 정시이고 색은 파랑`() {
        val at = Instant.parse("2026-10-20T03:00:03Z")

        val hourly = AccountPnlDiscordContent.hourly(pnl(at = at), zone)

        assertThat(hourly.title).isEqualTo("📊 정시 보고 12:00")
        assertThat(hourly.description).isEqualTo(AccountPnlDiscordContent.profit(pnl(at = at), zone).description)
        assertThat(hourly.color).isEqualTo(AccountPnlDiscordContent.COLOR_HOURLY)
    }

    @Test
    fun `미반영 항목은 한 줄로 사유와 함께 표시`() {
        val message = AccountPnlDiscordContent.profit(
            pnl(koreaFeeUnrecovered = true, foreignFeeUnrecovered = true, fundingUnrecovered = true),
            zone,
        )

        assertThat(message.description.lines()).contains(
            "⚠ 미반영: 진입 수수료(빗썸, 이력 없음), 진입 수수료(바이낸스, 이력 없음), 펀딩비(이력 없음)",
        )
    }

    @Test
    fun `수동 출처는 수동 미입력 사유와 출처 줄을 쓴다`() {
        val message = AccountPnlDiscordContent.profit(manual(pnl(foreignFeeUnrecovered = true, fundingUnrecovered = true)), zone)

        val lines = message.description.lines()
        assertThat(lines).contains("⚠ 미반영: 진입 수수료(바이낸스, 수동 미입력), 펀딩비(수동 미입력)")
        assertThat(lines.last()).isEqualTo("바이낸스 포지션: 수동 입력(env) · 2026-10-20 09:00 KST")
    }

    @Test
    fun `미반영이 없으면 경고 줄이 없다`() {
        assertThat(AccountPnlDiscordContent.profit(pnl(), zone).description).doesNotContain("미반영")
    }

    @Test
    fun `평단 수정이면 표시 줄이 있다`() {
        assertThat(AccountPnlDiscordContent.profit(pnl(modified = true), zone).description.lines())
            .contains("빗썸 평단 사용자 수정됨")
        assertThat(AccountPnlDiscordContent.profit(pnl(), zone).description).doesNotContain("평단 사용자 수정됨")
    }

    @Test
    fun `빗썸 인증 중단 Discord 는 golden 과 같다`() {
        val message = AccountPnlDiscordContent.outage(
            OutageNotice.Outage(AccountReadFailureCategory.BITHUMB_AUTH, null),
            now,
            zone,
        )

        assertThat(message.title).isEqualTo("🚨 모니터링 중단 — 빗썸 인증 실패(허용 IP 확인)")
        assertThat(message.description).isEqualTo(
            """
            실계정 순손익 모니터링이 5분 넘게 중단되었습니다.
            마지막 성공: 없음
            알림 시각: 2026-10-20 09:30 KST
            빗썸 Open API key 의 허용 IP 가 현재 공인 IP 와 같은지 확인하세요.
            조치 안내: docs/runbooks/account-pnl-alert.md
            """.trimIndent(),
        )
        assertThat(message.color).isEqualTo(AccountPnlDiscordContent.COLOR_OUTAGE)
    }

    @Test
    fun `환율 지연 중단은 IP 안내 줄이 없다`() {
        val message = AccountPnlDiscordContent.outage(
            OutageNotice.Outage(AccountReadFailureCategory.STALE_FX, Instant.parse("2026-10-20T00:00:00Z")),
            now,
            zone,
        )

        assertThat(message.title).isEqualTo("🚨 모니터링 중단 — 환율 수집 지연")
        assertThat(message.description).isEqualTo(
            """
            실계정 순손익 모니터링이 5분 넘게 중단되었습니다.
            마지막 성공: 2026-10-20 09:00 KST
            알림 시각: 2026-10-20 09:30 KST
            조치 안내: docs/runbooks/account-pnl-alert.md
            """.trimIndent(),
        )
    }

    @Test
    fun `복구 Discord 는 golden 과 같다`() {
        val message = AccountPnlDiscordContent.recovered(now, zone)

        assertThat(message.title).isEqualTo("✅ 모니터링 복구")
        assertThat(message.description)
            .isEqualTo("실계정 순손익 모니터링이 복구되었습니다.\n복구 시각: 2026-10-20 09:30 KST")
        assertThat(message.color).isEqualTo(AccountPnlDiscordContent.COLOR_RECOVERED)
    }

    @Test
    fun `네 종류 색 상수`() {
        assertThat(AccountPnlDiscordContent.COLOR_PROFIT).isEqualTo(3066993)
        assertThat(AccountPnlDiscordContent.COLOR_HOURLY).isEqualTo(3447003)
        assertThat(AccountPnlDiscordContent.COLOR_OUTAGE).isEqualTo(15158332)
        assertThat(AccountPnlDiscordContent.COLOR_RECOVERED).isEqualTo(9807270)
    }
}
