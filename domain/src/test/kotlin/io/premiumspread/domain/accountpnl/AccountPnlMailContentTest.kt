package io.premiumspread.domain.accountpnl

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId

class AccountPnlMailContentTest {

    private val zone = ZoneId.of("Asia/Seoul")
    private val bidObservedAt = Instant.parse("2026-10-20T00:00:00Z")
    private val fxObservedAt = Instant.parse("2026-10-19T23:30:00Z")
    private val calculatedAt = Instant.parse("2026-10-20T00:00:05Z")
    private val fakeSecrets = listOf("test-access-key", "test-secret", "@example.com")

    private fun position(modified: Boolean = false) = AccountHedgePosition(
        KoreaSpotAccountSnapshot(
            quantity = BigDecimal("0.266"),
            averageBuyPrice = BigDecimal("113016278"),
            averageBuyPriceModified = modified,
            entryFeeKrw = BigDecimal("12000"),
            sellFeeRate = BigDecimal("0.0004"),
            bidLevels = listOf(PriceLevel(BigDecimal("109500000"), BigDecimal("1.0"))),
            lastPrice = BigDecimal("109575000"),
            bidObservedAt = bidObservedAt,
        ),
        ForeignPerpAccountSnapshot(
            signedQuantity = BigDecimal("-0.266"),
            entryPrice = BigDecimal("83549.4"),
            entryCommissionUsdt = BigDecimal("8.9"),
            fundingUsdt = BigDecimal("3.2"),
            takerFeeRate = BigDecimal("0.0005"),
            askLevels = listOf(PriceLevel(BigDecimal("81580"), BigDecimal("5.0"))),
            lastPrice = BigDecimal("81577"),
            askObservedAt = Instant.parse("2026-10-20T00:00:01Z"),
        ),
    )

    private fun pnl(
        netKrw: String = "456789.4",
        netPercent: String = "1.23",
        modified: Boolean = false,
        koreaFeeUnrecovered: Boolean = false,
        foreignFeeUnrecovered: Boolean = false,
        fundingUnrecovered: Boolean = false,
    ) = AccountNetPnl(
        grossKrw = BigDecimal("500000.5"),
        entryFeeKrw = BigDecimal("23826.65"),
        fundingKrw = BigDecimal("4251.2"),
        exitFeeEstimateKrw = BigDecimal("15000.1"),
        netKrw = BigDecimal(netKrw),
        netPercent = BigDecimal(netPercent),
        grossKrwAtLast = BigDecimal("490000"),
        exitFeeEstimateKrwAtLast = BigDecimal("15000"),
        netKrwAtLast = BigDecimal("455000"),
        netPercentAtLast = BigDecimal("1.20"),
        koreaExitPrice = BigDecimal("109500000"),
        foreignExitPrice = BigDecimal("81580"),
        koreaEntryFeeUnrecovered = koreaFeeUnrecovered,
        foreignEntryFeeUnrecovered = foreignFeeUnrecovered,
        fundingUnrecovered = fundingUnrecovered,
        position = position(modified),
        fxRate = BigDecimal("1328.5"),
        fxObservedAt = fxObservedAt,
        calculatedAt = calculatedAt,
    )

    @Test
    fun `제목에 순수익률과 순수익금이 있다`() {
        val mail = AccountPnlMailContent.profit(pnl(), zone)

        assertThat(mail.subject).isEqualTo("[김프 알림] 순수익률 +1.23% · +456,789원")
        assertThat(mail.eventKey).isEqualTo("profit|2026-10-20T00:00:05Z")
    }

    @Test
    fun `음수는 마이너스 부호를 쓴다`() {
        val mail = AccountPnlMailContent.profit(pnl(netKrw = "-285018.5", netPercent = "-0.98"), zone)

        assertThat(mail.subject).isEqualTo("[김프 알림] 순수익률 -0.98% · -285,019원")
    }

    @Test
    fun `본문에 계산 근거가 모두 있다`() {
        val body = AccountPnlMailContent.profit(pnl(), zone).body

        assertThat(body).contains(
            "순수익률", "순수익금", "gross", "진입 수수료", "누적 펀딩비", "예상 청산 수수료",
            "체결가 기준 순수익률", "수량", "평단", "진입가", "청산가", "최근 체결가", "환율",
            "빗썸 호가 관측", "바이낸스 호가 관측", "환율 관측", "계산 시각",
            "+1.23%", "+456,789원", "+1.20%", "113,016,278", "0.266", "83549.4", "1328.5",
            "2026-10-20 09:00:00 (2026-10-20T00:00:00Z)",
            "2026-10-20 08:30:00 (2026-10-19T23:30:00Z)",
            "2026-10-20 09:00:05 (2026-10-20T00:00:05Z)",
        )
        assertThat(body).doesNotContain("미반영", "평단 사용자 수정됨")
    }

    @Test
    fun `미반영 항목을 명시한다`() {
        val body = AccountPnlMailContent.profit(
            pnl(koreaFeeUnrecovered = true, foreignFeeUnrecovered = true, fundingUnrecovered = true),
            zone,
        ).body

        assertThat(body).contains(
            "진입 수수료(빗썸) 미반영(이력 없음)",
            "진입 수수료(바이낸스) 미반영(이력 없음)",
            "펀딩비 미반영(이력 없음)",
        )
    }

    @Test
    fun `평단 수정 표시를 넣는다`() {
        assertThat(AccountPnlMailContent.profit(pnl(modified = true), zone).body).contains("빗썸 평단 사용자 수정됨")
    }

    @Test
    fun `중단 메일 제목에 범주가 있다`() {
        val now = Instant.parse("2026-10-20T01:00:00Z")
        val expected = mapOf(
            AccountReadFailureCategory.BITHUMB_AUTH to "빗썸 인증 실패(허용 IP 확인)",
            AccountReadFailureCategory.BINANCE_AUTH to "바이낸스 인증 실패",
            AccountReadFailureCategory.NETWORK to "네트워크·요청 한도 오류",
            AccountReadFailureCategory.STALE_MARKET_DATA to "시세 지연",
            AccountReadFailureCategory.STALE_FX to "환율 수집 지연",
            AccountReadFailureCategory.INSUFFICIENT_DEPTH to "호가 깊이 부족",
            AccountReadFailureCategory.UNEXPECTED to "기타 오류(로그 확인)",
        )
        assertThat(expected.keys).containsExactlyInAnyOrder(*AccountReadFailureCategory.entries.toTypedArray())

        expected.forEach { (category, label) ->
            val mail = AccountPnlMailContent.outage(OutageNotice.Outage(category, bidObservedAt), now, zone)
            assertThat(mail.subject).isEqualTo("[김프 알림] 모니터링 중단 — $label")
            assertThat(mail.eventKey).isEqualTo("outage|2026-10-20T01:00:00Z")
            assertThat(mail.body).contains(label, "2026-10-20 09:00:00 (2026-10-20T00:00:00Z)", "docs/runbooks/account-pnl-alert.md")
        }
    }

    @Test
    fun `중단 본문은 인증 힌트와 마지막 성공 없음을 표시한다`() {
        val now = Instant.parse("2026-10-20T01:00:00Z")

        val auth = AccountPnlMailContent.outage(OutageNotice.Outage(AccountReadFailureCategory.BITHUMB_AUTH, null), now, zone)
        val other = AccountPnlMailContent.outage(OutageNotice.Outage(AccountReadFailureCategory.NETWORK, null), now, zone)

        assertThat(auth.body).contains("허용 IP", "없음")
        assertThat(other.body).doesNotContain("허용 IP")
    }

    @Test
    fun `복구 메일에 복구 시각이 있다`() {
        val mail = AccountPnlMailContent.recovered(Instant.parse("2026-10-20T01:00:00Z"), zone)

        assertThat(mail.subject).isEqualTo("[김프 알림] 모니터링 복구")
        assertThat(mail.eventKey).isEqualTo("recovered|2026-10-20T01:00:00Z")
        assertThat(mail.body).contains("2026-10-20 10:00:00 (2026-10-20T01:00:00Z)")
    }

    @Test
    fun `제목과 본문에 비밀 문자열이 없다`() {
        val now = Instant.parse("2026-10-20T01:00:00Z")
        val mails = listOf(
            AccountPnlMailContent.profit(pnl(), zone),
            AccountPnlMailContent.outage(OutageNotice.Outage(AccountReadFailureCategory.BINANCE_AUTH, null), now, zone),
            AccountPnlMailContent.recovered(now, zone),
        )
        mails.forEach { mail ->
            fakeSecrets.forEach { secret ->
                assertThat(mail.subject).doesNotContain(secret)
                assertThat(mail.body).doesNotContain(secret)
            }
        }
    }
}
