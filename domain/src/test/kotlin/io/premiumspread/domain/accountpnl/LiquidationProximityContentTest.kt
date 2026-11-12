package io.premiumspread.domain.accountpnl

import io.premiumspread.domain.alertmessage.EmbedCell
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId

class LiquidationProximityContentTest {

    private val zone = ZoneId.of("Asia/Seoul")
    private val at = Instant.parse("2026-10-20T03:00:00Z")
    private val isolated5x = ForeignPerpSymbolConfig(5, ForeignPerpMarginType.ISOLATED)

    private fun proximity(config: ForeignPerpSymbolConfig? = isolated5x) = AccountAlert.LiquidationProximity(
        level = 80,
        progressPercent = BigDecimal("85.2345"),
        side = PerpSide.SHORT,
        entryPrice = BigDecimal("70000"),
        markPrice = BigDecimal("80230.5"),
        liquidationPrice = BigDecimal("82000"),
        config = config,
        observedAt = at,
    )

    private fun eased() = AccountAlert.LiquidationEased(
        previousLevel = 60,
        armedLevel = 50,
        progressPercent = BigDecimal("45.06"),
        side = PerpSide.SHORT,
        entryPrice = BigDecimal("70000"),
        markPrice = BigDecimal("75407.2"),
        liquidationPrice = BigDecimal("82000"),
        config = ForeignPerpSymbolConfig(10, ForeignPerpMarginType.CROSSED),
        observedAt = at,
    )

    @Test
    fun `제목과 eventKey`() {
        val alert = proximity()
        val m = AccountAlertContent.message(alert, zone)

        assertThat(m.eventKey).isEqualTo("account-pnl-liquidation-80-${at.toEpochMilli()}")
        assertThat(AccountAlertContent.eventKey(alert)).isEqualTo(m.eventKey)
        assertThat(m.eventKey).matches("[A-Za-z0-9._-]{1,128}")
        assertThat(m.mail.subject).isEqualTo("[실계정] 청산 근접 80%")
        assertThat(m.discord.title).isEqualTo("🚨 청산 근접 80%")
        assertThat(m.discord.author).isEqualTo("실계정 · BTC")
        assertThat(m.discord.timestamp).isEqualTo(at)
    }

    @Test
    fun `메일과 Discord 양쪽에 진행률 가격 레버리지 마진 모드가 있다`() {
        val m = AccountAlertContent.message(proximity(), zone)
        val cells = m.discord.rows.flatten()
        val body = m.mail.body()

        val expected = listOf(
            "진행률" to "85.2%",
            "현재 마크가" to "80,230.5 USDT",
            "청산가" to "82,000 USDT",
            "진입가" to "70,000 USDT",
            "레버리지" to "5x",
            "마진 모드" to "격리",
        )
        expected.forEach { (label, value) ->
            assertThat(cells).contains(EmbedCell(label, value))
            assertThat(body).contains(label).contains(value)
        }
        assertThat(m.discord.footer)
            .isEqualTo("진입가→청산가 진행률 · 50%부터 10% 단위 · 같은 경계는 한 단계 내려간 뒤 다시 알림")
        assertThat(body).contains("기준 시각 2026-10-20 12:00 KST")
        assertThat(body).doesNotContain("BTC")
    }

    @Test
    fun `심볼 설정이 없으면 레버리지와 마진 모드는 확인 불가다`() {
        val m = AccountAlertContent.message(proximity(config = null), zone)
        val cells = m.discord.rows.flatten()

        assertThat(cells).contains(EmbedCell("레버리지", "확인 불가"), EmbedCell("마진 모드", "확인 불가"))
        assertThat(m.mail.body()).contains("확인 불가")
    }

    @Test
    fun `완화 문구`() {
        val alert = eased()
        val m = AccountAlertContent.message(alert, zone)
        val cells = m.discord.rows.flatten()
        val body = m.mail.body()

        assertThat(m.eventKey).isEqualTo("account-pnl-liquidation-eased-50-${at.toEpochMilli()}")
        assertThat(AccountAlertContent.eventKey(alert)).isEqualTo(m.eventKey)
        assertThat(m.mail.subject).isEqualTo("[실계정] 청산 위험 완화 45.1%")
        assertThat(m.discord.title).isEqualTo("✅ 청산 위험 완화 45.1%")
        assertThat(cells).contains(
            EmbedCell("직전 알림 경계", "60%"),
            EmbedCell("진행률", "45.1%"),
            EmbedCell("레버리지", "10x"),
            EmbedCell("마진 모드", "교차"),
        )
        assertThat(body).contains("직전 알림 경계").contains("60%").contains("교차")
        assertThat(m.discord.footer).isEqualTo("알린 경계보다 한 단계 아래로 내려가 다시 알릴 수 있습니다")
    }
}
